package com.github.h3yow.tenantquotagatewayjava.quota;

import com.github.h3yow.tenantquotagatewayjava.config.GatewayProperties;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配额网关核心判定服务。
 *
 * 判定顺序（每一步都有确定、可区分的原因）：
 * 1. 租户识别（外部完成）-> 2. 配置可用性/降级 -> 3. 租户是否配置
 * -> 4. 幂等占坑（重复提交重放首次结论，不再扣减）
 * -> 5. 速率限制（令牌桶）-> 6. 配额计量（原子占用）。
 *
 * 每个租户使用独立的令牌桶与独立的计量锁，任一租户的突发流量不会阻塞或影响其他租户。
 * 策略刷新通过不可变快照原子替换完成：在途请求要么全用旧策略、要么全用新策略，
 * 令牌桶平滑更新（保留已积累令牌），配额用量不重置，因此刷新期间不会判定错乱或用量丢失。
 */
public class QuotaGatewayService {

    private final PolicyRegistry registry;
    private final QuotaLedger ledger;
    private final AuditLog auditLog;
    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public QuotaGatewayService(PolicyRegistry registry,
                               QuotaLedger ledger,
                               AuditLog auditLog,
                               GatewayProperties properties) {
        this.registry = registry;
        this.ledger = ledger;
        this.auditLog = auditLog;
        this.properties = properties;
    }

    public Decision decide(String tenantId, String requestId) {
        long version = registry.version();

        if (tenantId == null || tenantId.isBlank()) {
            return rejectAndAudit(RejectionReason.TENANT_NOT_IDENTIFIED, null, requestId, version,
                    "请求缺少租户标识头 " + TenantResolver.TENANT_HEADER);
        }

        // 降级链路：配置不可用时，结果由配置决定，确定且原因可区分；放行时不扣减任何用量
        if (!registry.isAvailable()) {
            if ("FAIL_OPEN".equalsIgnoreCase(properties.getDegradationMode())) {
                Decision decision = new Decision(true, RejectionReason.POLICY_UNAVAILABLE_ALLOWED,
                        "策略配置不可用，按 FAIL_OPEN 语义放行，不计费不限流",
                        tenantId, requestId, version);
                auditLog.record(entry(decision, decision.detail()));
                return decision;
            }
            return rejectAndAudit(RejectionReason.POLICY_UNAVAILABLE_REJECTED, tenantId, requestId, version,
                    "策略配置不可用，按 FAIL_CLOSED 语义拒绝");
        }

        TenantPolicy policy = registry.find(tenantId);
        if (policy == null) {
            if ("ALLOW".equalsIgnoreCase(properties.getUnknownTenantMode())) {
                return Decision.allow(tenantId, requestId, version, "租户未配置策略，按 unknown-tenant ALLOW 放行");
            }
            return rejectAndAudit(RejectionReason.TENANT_NOT_CONFIGURED, tenantId, requestId, version,
                    "租户 " + tenantId + " 不存在生效策略（policyVersion=" + version + "）");
        }

        // 幂等：同一 requestId 的并发/重试只会有一个请求执行后续判定
        try {
            QuotaLedger.Begin begin = ledger.begin(tenantId, requestId, policy.quotaWindowMillis());
            if (begin instanceof QuotaLedger.Begin.Replay replay) {
                if (replay.allowed()) {
                    return Decision.allow(tenantId, requestId, version,
                            "重复提交/重试命中幂等记录，重放首次结论=ALLOWED，未重复扣减用量");
                }
                return rejectAndAudit(replay.reason(), tenantId, requestId, version,
                        "重复提交/重试命中幂等记录，重放首次结论=REJECTED(" + replay.reason() + ")");
            }
        } catch (RuntimeException e) {
            return degradedOnInternalError(tenantId, requestId, version, "幂等状态异常: " + e);
        }

        try {
            TokenBucket bucket = buckets.computeIfAbsent(tenantId,
                    k -> new TokenBucket(policy.rateLimitPerSecond(), policy.burstCapacity()));
            long available = bucket.availableTokens();
            if (!bucket.tryConsume()) {
                ledger.commitRejection(tenantId, requestId, RejectionReason.RATE_LIMIT_EXCEEDED,
                        policy.quotaWindowMillis());
                return rejectAndAudit(RejectionReason.RATE_LIMIT_EXCEEDED, tenantId, requestId, version,
                        "速率限制: rate=" + policy.rateLimitPerSecond() + "/s, burst="
                                + policy.burstCapacity() + ", 剩余令牌≈" + available);
            }

            long before = ledger.currentUsage(tenantId);
            boolean acquired;
            try {
                acquired = ledger.commitQuota(tenantId, requestId, policy.quotaLimit(),
                        policy.quotaWindowMillis());
            } catch (RuntimeException e) {
                // 令牌已扣但配额状态异常：确定性降级，绝不返回含糊结果
                return degradedOnInternalError(tenantId, requestId, version, "配额计量异常: " + e);
            }
            if (!acquired) {
                return rejectAndAudit(RejectionReason.QUOTA_EXCEEDED, tenantId, requestId, version,
                        "配额已满: used=" + before + "/" + policy.quotaLimit()
                                + ", window=" + policy.quotaWindowMillis() + "ms");
            }

            String detail = "速率与配额判定通过: 占用配额 " + (before + 1) + "/" + policy.quotaLimit()
                    + "，剩余令牌≈" + bucket.availableTokens();
            return Decision.allow(tenantId, requestId, version, detail);
        } catch (RuntimeException e) {
            ledger.abort(tenantId, requestId);
            return degradedOnInternalError(tenantId, requestId, version, "内部状态异常: " + e);
        }
    }

    /**
     * 用最新策略快照同步令牌桶：新租户建桶、存量租户平滑更新（令牌保留且不超过新容量）。
     * 配额账本不重置：已计用量在当前窗口内继续有效，保证刷新不丢用量。
     */
    public synchronized void syncRateLimiters() {
        for (TenantPolicy policy : registry.snapshot().values()) {
            TokenBucket bucket = buckets.get(policy.tenantId());
            if (bucket == null) {
                buckets.computeIfAbsent(policy.tenantId(),
                        k -> new TokenBucket(policy.rateLimitPerSecond(), policy.burstCapacity()));
            } else {
                bucket.update(policy.rateLimitPerSecond(), policy.burstCapacity());
            }
        }
    }

    /** 策略刷新：原子替换快照 + 平滑同步限流器，全程可审计 */
    public long refreshPolicies(java.util.List<TenantPolicy> policies) {
        long version = registry.replaceAll(policies);
        syncRateLimiters();
        return version;
    }

    private Decision degradedOnInternalError(String tenantId, String requestId, long version, String basis) {
        if ("FAIL_OPEN".equalsIgnoreCase(properties.getDegradationMode())) {
            Decision decision = new Decision(true, RejectionReason.POLICY_UNAVAILABLE_ALLOWED,
                    basis + "；按 FAIL_OPEN 语义放行", tenantId, requestId, version);
            auditLog.record(entry(decision, basis));
            return decision;
        }
        return rejectAndAudit(RejectionReason.POLICY_UNAVAILABLE_REJECTED, tenantId, requestId, version,
                basis + "；按 FAIL_CLOSED 语义拒绝");
    }

    private Decision rejectAndAudit(RejectionReason reason, String tenantId, String requestId,
                                    long version, String basis) {
        Decision decision = Decision.reject(reason, tenantId, requestId, version, basis);
        auditLog.record(entry(decision, basis));
        return decision;
    }

    private AuditEntry entry(Decision decision, String basis) {
        return new AuditEntry(Instant.now(), decision.requestId(), decision.tenantId(),
                decision.allowed(), decision.reason(), decision.policyVersion(),
                basis + " | " + describePolicy(decision.tenantId(), decision.policyVersion()));
    }

    private String describePolicy(String tenantId, long version) {
        if (tenantId == null) {
            return "policyVersion=" + version;
        }
        TenantPolicy policy = registry.find(tenantId);
        if (policy == null) {
            return "policyVersion=" + version + ", 无策略";
        }
        return "policyVersion=" + version + ", policy{rate=" + policy.rateLimitPerSecond()
                + ", burst=" + policy.burstCapacity() + ", quota=" + policy.quotaLimit()
                + "/" + policy.quotaWindowMillis() + "ms}";
    }

    /** 测试/诊断用：当前各租户剩余令牌快照 */
    public Map<String, Long> availableTokensSnapshot() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        buckets.forEach((tenant, bucket) -> result.put(tenant, bucket.availableTokens()));
        return result;
    }
}
