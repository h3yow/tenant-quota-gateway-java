package com.github.h3yow.tenantquotagatewayjava.limit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 多租户限流/配额判定引擎。
 *
 * <p>一致性保证：</p>
 * <ul>
 *   <li>每个租户一把独立锁（{@link TenantState} 内置监视器），速率扣减与配额扣减在同一把
 *       锁内原子完成，不会超发或少计；不同租户的突发流量互不阻塞、互不影响。</li>
 *   <li>策略以不可变快照整体替换（{@link PolicySnapshot}），进行中的判定永远使用旧快照或
 *       新快照中的一个，不会出现半刷新；已允许请求的扣减不会因刷新丢失。</li>
 *   <li>相同幂等键的重复提交/重试直接返回首次判定，绝不二次扣减。</li>
 *   <li>内部状态或配置异常时按配置 fail-open / fail-closed 降级，原因区分为
 *       {@link DecisionReason#DEGRADED_DENY}。</li>
 * </ul>
 */
public class QuotaLimitEngine {

    /** 幂等存储中的占位条目：表示首个请求仍在判定中，并发重试在其上等待。 */
    private static final class InFlight {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Decision decision;
    }

    private final PolicyProvider policyProvider;
    private final DefaultAuditLogger auditLogger;
    private final Clock clock;

    private final boolean failOpen;
    private final long idempotencyTtlMillis;

    /** 租户状态表：仅在首次出现租户时放入，之后原地更新，策略刷新不清理用量。 */
    private final ConcurrentHashMap<String, TenantState> tenantStates = new ConcurrentHashMap<>();

    /** 幂等键 -> 已完成的首次判定（惰性过期）。 */
    private final ConcurrentHashMap<String, IdempotencyRecord> idempotencyStore =
            new ConcurrentHashMap<>();

    /** 正在判定中的幂等键 -> 占位条目，保证同一键的并发请求串行去重。 */
    private final ConcurrentHashMap<String, InFlight> inFlight = new ConcurrentHashMap<>();

    public QuotaLimitEngine(PolicyProvider policyProvider, DefaultAuditLogger auditLogger, Clock clock,
                            boolean failOpen, long idempotencyTtlMillis) {
        this.policyProvider = policyProvider;
        this.auditLogger = auditLogger;
        this.clock = clock;
        this.failOpen = failOpen;
        this.idempotencyTtlMillis = Math.max(0L, idempotencyTtlMillis);
    }

    /**
     * 对单个业务请求执行判定。
     *
     * @param requestId      请求标识（用于审计与测试输出）
     * @param tenantId       租户标识
     * @param idempotencyKey 幂等键，可为 null
     * @return 确定的判定结果
     */
    public Decision evaluate(String requestId, String tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doEvaluate(requestId, tenantId, null);
        }
        return evaluateIdempotent(requestId, tenantId, idempotencyKey.trim());
    }

    private Decision evaluateIdempotent(String requestId, String tenantId, String key) {
        long now = clock.now().toEpochMilli();
        IdempotencyRecord existing = idempotencyStore.get(key);
        if (existing != null && !existing.expiredAt(now)) {
            return replay(requestId, tenantId, key, existing);
        }

        InFlight placeHolder = new InFlight();
        InFlight winner = inFlight.putIfAbsent(key, placeHolder);
        if (winner != null) {
            // 同键的另一请求正在判定：等待其结论后重放，自身绝不扣减
            return awaitAndReplay(requestId, tenantId, key, winner);
        }

        Decision decision;
        try {
            decision = doEvaluate(requestId, tenantId, key);
            // 只有确定成功的首次判定才去重缓存；被限流/配额拒绝的重试应在额度恢复后可再尝试
            if (decision.reason() == DecisionReason.ALLOWED) {
                idempotencyStore.put(key, new IdempotencyRecord(
                        key, requestId, decision, now + idempotencyTtlMillis));
            }
            placeHolder.decision = decision;
        } finally {
            placeHolder.done.countDown();
            inFlight.remove(key, placeHolder);
        }
        return decision;
    }

    private Decision awaitAndReplay(String requestId, String tenantId, String key,
                                    InFlight holder) {
        try {
            if (!holder.done.await(10, TimeUnit.SECONDS)) {
                return degraded(requestId, tenantId, key, null,
                        "等待同幂等键首请求判定超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return degraded(requestId, tenantId, key, null,
                    "等待同幂等键首请求判定被中断");
        }
        // 直接使用 holder 上的首请求结论：该字段在 countDown 前写入，
        // await 返回后有 happens-before 保证可见；等待者绝不重新判定，杜绝二次扣减。
        Decision first = holder.decision;
        if (first == null) {
            return degraded(requestId, tenantId, key, null,
                    "同幂等键首请求判定结论缺失");
        }
        if (first.allowed()) {
            return replayOf(requestId, tenantId, key, first.requestId(), first);
        }
        // 首请求被拒绝：并发同键请求共享同一拒绝结论（原因/状态码一致），自身不独立扣减，
        // 也不伪装成成功的幂等重放
        String basis = "并发同键请求共享首请求 requestId=" + first.requestId()
                + " 的拒绝结论(" + first.reason() + ")，未独立扣减";
        Decision shared = new Decision(requestId, tenantId, key, first.reason(),
                first.policyVersion(), first.rateRemaining(), first.quotaRemaining(),
                first.quotaLimit(), first.retryAfterMillis(), basis);
        auditLogger.log(auditLogger.newEvent(AuditEvent.Kind.DENY, requestId, tenantId,
                first.reason(), first.policyVersion(), basis));
        return shared;
    }

    private Decision replay(String requestId, String tenantId, String key,
                            IdempotencyRecord record) {
        return replayOf(requestId, tenantId, key, record.firstRequestId(),
                record.decision());
    }

    private Decision replayOf(String requestId, String tenantId, String key,
                              String firstRequestId, Decision first) {
        Decision replay = new Decision(requestId, tenantId, key,
                DecisionReason.IDEMPOTENT_REPLAY, first.policyVersion(),
                first.rateRemaining(), first.quotaRemaining(), first.quotaLimit(), 0L,
                "幂等重放: 与首次请求 requestId=" + firstRequestId
                        + " 判定一致(" + first.reason() + ")，未重复扣减速率/配额");
        auditLogger.log(auditLogger.newEvent(AuditEvent.Kind.IDEMPOTENT_REPLAY,
                requestId, tenantId, DecisionReason.IDEMPOTENT_REPLAY, first.policyVersion(),
                replay.detail()));
        return replay;
    }

    private Decision doEvaluate(String requestId, String tenantId, String idempotencyKey) {
        if (tenantId == null || tenantId.isBlank()) {
            return deny(requestId, null, idempotencyKey, DecisionReason.MISSING_TENANT, null,
                    -1, -1, -1, 0, "请求缺少租户标识，无法匹配任何策略");
        }
        tenantId = tenantId.trim();

        final PolicySnapshot snapshot;
        try {
            snapshot = policyProvider.currentSnapshot();
        } catch (RuntimeException e) {
            return degraded(requestId, tenantId, idempotencyKey, null,
                    "策略不可用: " + e.getMessage());
        }
        if (snapshot == null) {
            return degraded(requestId, tenantId, idempotencyKey, null, "策略快照为空");
        }

        TenantPolicy policy = snapshot.policyOf(tenantId);
        if (policy == null) {
            return deny(requestId, tenantId, idempotencyKey, DecisionReason.UNKNOWN_TENANT,
                    snapshot.version(), -1, -1, -1, 0,
                    "租户在策略版本 v" + snapshot.version() + " 中不存在");
        }

        try {
            TenantState state =
                    tenantStates.computeIfAbsent(tenantId, t -> new TenantState(t,
                            clock.now().toEpochMilli()));
            long now = clock.now().toEpochMilli();
            TenantState.AcquireOutcome outcome;
            synchronized (state) {
                outcome = state.tryAcquire(policy, snapshot.version(), now);
            }

            String basis = switch (outcome.reason()) {
                case ALLOWED -> "已在租户锁内原子扣减1令牌+1配额: 速率桶 "
                        + policy.rateCapacity() + "/突发, 补充 "
                        + policy.rateRefillPerSecond() + "/s; 配额 "
                        + (policy.quotaLimit() - outcome.quotaRemaining()) + "/"
                        + policy.quotaLimit() + " (窗口 " + policy.quotaWindowSeconds()
                        + "s); 剩余令牌=" + outcome.rateRemaining() + " 剩余配额="
                        + outcome.quotaRemaining();
                case RATE_LIMITED -> "速率桶可用令牌不足1: 剩余令牌=" + outcome.rateRemaining()
                        + ", 容量=" + policy.rateCapacity() + ", 补充="
                        + policy.rateRefillPerSecond() + "/s, 建议重试等待="
                        + outcome.retryAfterMillis() + "ms";
                case QUOTA_EXHAUSTED -> "窗口配额已用尽: 配额 " + policy.quotaLimit() + "/"
                        + policy.quotaLimit() + " (窗口 " + policy.quotaWindowSeconds()
                        + "s), 窗口重置建议等待=" + outcome.retryAfterMillis() + "ms";
                default -> outcome.reason().name();
            };

            Decision decision = new Decision(requestId, tenantId, idempotencyKey,
                    outcome.reason(), snapshot.version(), outcome.rateRemaining(),
                    outcome.quotaRemaining(), policy.quotaLimit(),
                    outcome.retryAfterMillis(), basis);

            if (decision.allowed()) {
                return decision;
            }
            auditLogger.log(auditLogger.newEvent(AuditEvent.Kind.DENY, requestId, tenantId,
                    outcome.reason(), snapshot.version(), basis));
            return decision;
        } catch (RuntimeException e) {
            return degraded(requestId, tenantId, idempotencyKey, snapshot.version(),
                    "租户计量状态异常: " + e.getClass().getSimpleName() + " "
                            + e.getMessage());
        }
    }

    private Decision degraded(String requestId, String tenantId, String idempotencyKey,
                              Long version, String detail) {
        DecisionReason reason = failOpen
                ? DecisionReason.ALLOWED
                : DecisionReason.DEGRADED_DENY;
        String basis = "降级(" + (failOpen ? "fail-open 放行" : "fail-closed 拒绝")
                + "): " + detail;
        Decision decision = new Decision(requestId, tenantId, idempotencyKey, reason, version,
                -1, -1, -1, 0, basis);
        auditLogger.log(auditLogger.newEvent(AuditEvent.Kind.DEGRADATION, requestId,
                tenantId, reason, version, basis));
        return decision;
    }

    private Decision deny(String requestId, String tenantId, String idempotencyKey,
                          DecisionReason reason, Long version, long rateRemaining,
                          long quotaRemaining, long quotaLimit, long retryAfter,
                          String basis) {
        Decision decision = new Decision(requestId, tenantId, idempotencyKey, reason, version,
                rateRemaining, quotaRemaining, quotaLimit, retryAfter, basis);
        auditLogger.log(auditLogger.newEvent(AuditEvent.Kind.DENY, requestId, tenantId, reason,
                version, basis));
        return decision;
    }

    /** 管理端：查看某租户当前计量状态（不存在返回空）。 */
    public Optional<Map<String, Object>> describeTenant(String tenantId) {
        TenantState state = tenantStates.get(tenantId);
        if (state == null) {
            return Optional.empty();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        synchronized (state) {
            view.put("tenantId", state.tenantId());
            view.put("tokens", state.currentTokens());
            view.put("quotaUsed", state.quotaUsed());
            view.put("windowStartEpochMilli", state.windowStartEpochMilli());
        }
        return Optional.of(view);
    }

    /** 管理端：主动触发策略刷新并审计变更。 */
    public PolicySnapshot reloadPolicies() {
        // 先在替换前读取旧版本，保证审计依据准确
        PolicySnapshot previous;
        try {
            previous = policyProvider.currentSnapshot();
        } catch (RuntimeException e) {
            previous = null;
        }
        Long oldVersion = previous == null ? null : previous.version();
        PolicySnapshot next = policyProvider.reload();
        auditLogger.log(auditLogger.newEvent(AuditEvent.Kind.POLICY_CHANGE, null, null, null,
                next.version(), "策略刷新: 版本 " + oldVersion + " -> v" + next.version()
                        + ", 租户数 " + next.policies().size()));
        return next;
    }
}
