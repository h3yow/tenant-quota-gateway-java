package com.github.h3yow.tenantquotagatewayjava.limit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个租户的可变计量状态：令牌桶 + 当前配额窗口用量 + 在途预占。
 * 状态仅在持有该租户对象内置监视器锁时变更，不同租户使用不同锁，互不阻塞。
 *
 * <p>配额记账不变式：{@code quotaUsed = 已结算/普通消耗 + 当前窗口全部仍有效(ACTIVE 未超时)占用}。
 * 因此并发预占看到的剩余额度天然扣除了别人占住但尚未结算的量，不会重复使用、不会算漏。</p>
 */
final class TenantState {

    /**
     * 一次普通（一次性计量）尝试的内部结果。{@code expiredInPass} 为本次判定顺带扫描出的
     * 超时预占（已释放占用），由引擎负责审计。
     */
    record AcquireOutcome(DecisionReason reason, long retryAfterMillis,
                          long rateRemaining, long quotaRemaining,
                          List<ReservationRecord> expiredInPass) {
    }

    /** 预占尝试的内部结果（锁内产生）。 */
    record ReservationOutcome(DecisionReason reason, ReservationRecord record,
                              long retryAfterMillis, long rateRemaining,
                              long quotaRemaining, List<ReservationRecord> expiredInPass) {
    }

    /** 结算尝试的内部结果（锁内产生）；adjustment 为多退(负)/少补(正)的配额变动量。 */
    record SettlementOutcome(DecisionReason reason, ReservationRecord record,
                             long adjustment, long quotaRemaining,
                             List<ReservationRecord> expiredInPass) {
    }

    private final String tenantId;

    /** 预占超时 TTL 与终态记录保留时长（由引擎按配置传入）。 */
    private final long reservationTtlMillis;
    private final long terminalRecordTtlMillis;

    // 令牌桶
    private double tokens;
    private long lastRefillEpochMilli;

    // 配额窗口
    private long windowStartEpochMilli;
    private long quotaUsed;

    /** 预占键 -> 预占记录（含在途与近期终态，终态记录惰性清理）。 */
    private final Map<String, ReservationRecord> reservations = new LinkedHashMap<>();

    // 当前状态所对应的策略指纹；策略刷新后据此决定重置规则
    private long appliedPolicyVersion = -1L;
    private long appliedQuotaLimit;
    private long appliedQuotaWindowMillis;

    TenantState(String tenantId, long nowEpochMilli) {
        this(tenantId, nowEpochMilli, 5 * 60_000L, 24 * 60 * 60_000L);
    }

    TenantState(String tenantId, long nowEpochMilli, long reservationTtlMillis,
                long terminalRecordTtlMillis) {
        this.tenantId = tenantId;
        this.lastRefillEpochMilli = nowEpochMilli;
        this.windowStartEpochMilli = nowEpochMilli;
        this.reservationTtlMillis = Math.max(1L, reservationTtlMillis);
        this.terminalRecordTtlMillis = Math.max(1L, terminalRecordTtlMillis);
    }

    /** 租户标识。 */
    String tenantId() {
        return tenantId;
    }

    /**
     * 普通一次性计量判定（不涉及预占的既有路径）：令牌桶 + 配额窗口在同一把锁内原子扣减。
     * 该路径的相对时序保持与历史完全一致（先速率后窗口滚动），仅在入口额外做一次
     * 超时占用扫描，使被占住却等不到结算的额度可按 TTL 自动回收。
     */
    synchronized AcquireOutcome tryAcquire(TenantPolicy policy, long policyVersion,
                                           long nowEpochMilli) {
        List<ReservationRecord> expired = sweepExpired(nowEpochMilli);

        // —— 策略刷新处理（仅在版本变化时执行一次）——
        if (policyVersion != appliedPolicyVersion) {
            applyNewPolicy(policy, policyVersion, nowEpochMilli);
        }

        // —— 1. 令牌桶按经过时间补充 ——
        long elapsed = nowEpochMilli - lastRefillEpochMilli;
        if (elapsed > 0) {
            tokens = Math.min(policy.rateCapacity(),
                    tokens + (elapsed / 1000.0) * policy.rateRefillPerSecond());
            lastRefillEpochMilli = nowEpochMilli;
        }

        // —— 2. 速率判定（不通过则配额窗口完全不动，保持历史时序）——
        if (tokens < 1.0d) {
            long retryAfter = retryForToken(policy);
            return new AcquireOutcome(DecisionReason.RATE_LIMITED, retryAfter,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy), expired);
        }

        // —— 3. 配额窗口滚动（在途占用迁移到新窗口）——
        rollWindowIfNeeded(policy, nowEpochMilli);

        // —— 4. 配额判定（不通过则令牌桶补充结果保留，但不扣令牌）——
        if (quotaUsed >= policy.quotaLimit()) {
            long retryAfter = retryForWindowReset(policy, nowEpochMilli);
            return new AcquireOutcome(DecisionReason.QUOTA_EXHAUSTED, retryAfter,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy), expired);
        }

        // —— 5. 两者皆可：同一把锁内原子扣减 ——
        tokens -= 1.0d;
        quotaUsed += 1L;
        return new AcquireOutcome(DecisionReason.ALLOWED, 0L,
                rateRemainingLocked(policy), quotaRemainingLocked(policy), expired);
    }

    /**
     * 预占：速率闸门(1 令牌) 与 配额闸门(预估量) 同时满足才占用，否则都不动。
     * 重复提交同一在途键直接幂等重放首次预占结果，绝不二次占用。
     */
    synchronized ReservationOutcome tryReserve(TenantPolicy policy, long policyVersion,
                                               long nowEpochMilli, String key,
                                               String requestId, long estimated) {
        List<ReservationRecord> expired = prepareLocked(policy, policyVersion, nowEpochMilli);

        ReservationRecord existing = reservations.get(key);
        if (existing != null && existing.status() == ReservationStatus.ACTIVE
                && nowEpochMilli < existing.expiresAtEpochMilli()) {
            return new ReservationOutcome(DecisionReason.RESERVATION_REPLAY, existing, 0L,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy), expired);
        }
        if (existing != null && existing.status() != ReservationStatus.ACTIVE) {
            // 键已处终态（已结算/已超时）：不能复用它发起新预占，与"未知键"明确区分
            return new ReservationOutcome(DecisionReason.RESERVATION_CLOSED, existing, 0L,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy), expired);
        }

        // 速率闸门
        if (tokens < 1.0d) {
            return new ReservationOutcome(DecisionReason.RATE_LIMITED, existing,
                    retryForToken(policy), rateRemainingLocked(policy),
                    quotaRemainingLocked(policy), expired);
        }
        // 配额闸门（quotaUsed 已含全部在途占用）
        if (quotaUsed + estimated > policy.quotaLimit()) {
            return new ReservationOutcome(DecisionReason.QUOTA_EXHAUSTED, existing,
                    retryForWindowReset(policy, nowEpochMilli), rateRemainingLocked(policy),
                    quotaRemainingLocked(policy), expired);
        }

        // 原子占用：1 速率令牌 + estimated 配额
        tokens -= 1.0d;
        quotaUsed += estimated;
        ReservationRecord record = new ReservationRecord(key, requestId, estimated,
                nowEpochMilli, reservationTtlMillis, windowStartEpochMilli, policyVersion);
        reservations.put(key, record);
        return new ReservationOutcome(DecisionReason.RESERVED, record, 0L,
                rateRemainingLocked(policy), quotaRemainingLocked(policy), expired);
    }

    /**
     * 结算：按真实用量对一笔在途预占多退少补。重复/乱序/迟到结果可区分且只生效一次：
     * 已结算返回 {@link DecisionReason#RESERVATION_REPLAY}；已超时返回
     * {@link DecisionReason#RESERVATION_EXPIRED}；未知键返回
     * {@link DecisionReason#RESERVATION_NOT_FOUND}。
     */
    synchronized SettlementOutcome settle(TenantPolicy policy, long policyVersion,
                                          long nowEpochMilli, String key,
                                          String requestId, long actual) {
        // 结算前确保窗口已滚动、在途占用已迁移到当前窗口，使补扣落在正确窗口与上限上
        List<ReservationRecord> expired = prepareLocked(policy, policyVersion, nowEpochMilli);
        ReservationRecord record = reservations.get(key);
        if (record == null) {
            return new SettlementOutcome(DecisionReason.RESERVATION_NOT_FOUND, null, 0L,
                    quotaRemainingLocked(policy), expired);
        }
        if (record.status() == ReservationStatus.EXPIRED
                || (record.status() == ReservationStatus.ACTIVE
                    && nowEpochMilli >= record.expiresAtEpochMilli())) {
            // 边界 now == expiresAt 即视为失效（确定性规则），迟到结算不能再对失效占用计费
            if (record.status() == ReservationStatus.ACTIVE) {
                expireRecord(record, nowEpochMilli);
                expired.add(record);
            }
            return new SettlementOutcome(DecisionReason.RESERVATION_EXPIRED, record, 0L,
                    quotaRemainingLocked(policy), expired);
        }
        if (record.status() == ReservationStatus.SETTLED) {
            return new SettlementOutcome(DecisionReason.RESERVATION_REPLAY, record, 0L,
                    quotaRemainingLocked(policy), expired);
        }

        // 多退(actual < reserved -> adjustment 负) / 少补(actual > reserved -> adjustment 正)。
        // 补扣允许瞬时超过上限（真实用量必须如实入账），之后新的预占/普通请求会被挡在门外。
        long adjustment = actual - record.amount();
        quotaUsed = Math.max(0L, quotaUsed + adjustment);
        record.markSettled(actual, requestId, nowEpochMilli);
        return new SettlementOutcome(DecisionReason.SETTLED, record, adjustment,
                quotaRemainingLocked(policy), expired);
    }

    /**
     * 预占/结算路径的共同前置：清理终态记录、超时扫描、应用新策略并补充令牌、
     * 滚动配额窗口（在途占用迁移）。与普通路径的差异是窗口滚动在速率判定之前完成，
     * 因为结算本身不消耗速率令牌，且必须落到当前窗口。
     */
    private List<ReservationRecord> prepareLocked(TenantPolicy policy, long policyVersion,
                                                  long nowEpochMilli) {
        List<ReservationRecord> expired = sweepExpired(nowEpochMilli);
        if (policyVersion != appliedPolicyVersion) {
            applyNewPolicy(policy, policyVersion, nowEpochMilli);
        }
        long elapsed = nowEpochMilli - lastRefillEpochMilli;
        if (elapsed > 0) {
            tokens = Math.min(policy.rateCapacity(),
                    tokens + (elapsed / 1000.0) * policy.rateRefillPerSecond());
            lastRefillEpochMilli = nowEpochMilli;
        }
        rollWindowIfNeeded(policy, nowEpochMilli);
        return expired;
    }

    /**
     * 超时扫描：到达 TTL 仍未结算的在途占用自动失效，占用量退回当前窗口。
     * 已正常结算的记录不受影响。规则确定：{@code now >= expiresAt} 即失效。
     */
    private List<ReservationRecord> sweepExpired(long nowEpochMilli) {
        purgeTerminalRecords(nowEpochMilli);
        List<ReservationRecord> expired = null;
        for (ReservationRecord r : reservations.values()) {
            if (r.status() == ReservationStatus.ACTIVE && nowEpochMilli >= r.expiresAtEpochMilli()) {
                expireRecord(r, nowEpochMilli);
                if (expired == null) {
                    expired = new ArrayList<>();
                }
                expired.add(r);
            }
        }
        return expired == null ? List.of() : expired;
    }

    private void expireRecord(ReservationRecord r, long nowEpochMilli) {
        quotaUsed = Math.max(0L, quotaUsed - r.amount());
        r.markExpired(nowEpochMilli);
    }

    /** 清理过了保留期的终态记录（SETTLED/EXPIRED），使内存有界；清理后重提结算按 NOT_FOUND 区分。 */
    private void purgeTerminalRecords(long nowEpochMilli) {
        reservations.values().removeIf(r ->
                r.status() != ReservationStatus.ACTIVE
                        && nowEpochMilli >= r.purgeableAt(terminalRecordTtlMillis));
    }

    /** 窗口滚动：旧窗口的已结算消耗清零；仍在途的占用改绑并迁移到新窗口，用量不凭空消失。 */
    private void rollWindowIfNeeded(TenantPolicy policy, long nowEpochMilli) {
        long windowLength = policy.quotaWindowSeconds() * 1000L;
        if (windowLength <= 0 || nowEpochMilli - windowStartEpochMilli < windowLength) {
            return;
        }
        long carried = carryActiveToWindow(nowEpochMilli);
        windowStartEpochMilli = nowEpochMilli;
        quotaUsed = carried;
    }

    /** 把当前窗口仍有效的在途占用改绑到即将生效的新窗口起点，并返回其总量。 */
    private long carryActiveToWindow(long newWindowStartEpochMilli) {
        long carried = 0L;
        for (ReservationRecord r : reservations.values()) {
            if (r.status() == ReservationStatus.ACTIVE
                    && newWindowStartEpochMilli < r.expiresAtEpochMilli()) {
                r.rebindWindow(newWindowStartEpochMilli);
                carried += r.amount();
            }
        }
        return carried;
    }

    /**
     * 策略版本变化时的重置规则：令牌桶按新容量重新充满；配额维度（上限或窗口长度）变化才开新
     * 窗口，否则保留当前窗口已用量——策略刷新不丢失既有用量；开新窗口时在途占用一并迁移，
     * 使刷新期间在途业务的判定与后续结算不错乱。
     */
    private void applyNewPolicy(TenantPolicy policy, long policyVersion, long nowEpochMilli) {
        boolean firstTime = appliedPolicyVersion == -1L;
        long newWindowMillis = policy.quotaWindowSeconds() * 1000L;
        boolean quotaShapeChanged = !firstTime
                && (appliedQuotaLimit != policy.quotaLimit()
                    || appliedQuotaWindowMillis != newWindowMillis);

        tokens = policy.rateCapacity();
        lastRefillEpochMilli = nowEpochMilli;

        if (firstTime || quotaShapeChanged) {
            long carried = firstTime ? 0L : carryActiveToWindow(nowEpochMilli);
            windowStartEpochMilli = nowEpochMilli;
            quotaUsed = carried;
        }
        appliedQuotaLimit = policy.quotaLimit();
        appliedQuotaWindowMillis = newWindowMillis;
        appliedPolicyVersion = policyVersion;
    }

    private long retryForToken(TenantPolicy policy) {
        double rate = policy.rateRefillPerSecond();
        if (rate <= 0.0d) {
            // 零补充速率且桶空：下一个配额窗口/人工调整前不会恢复
            return 0L;
        }
        double waitSeconds = (1.0d - tokens) / rate;
        return Math.max(1L, (long) Math.ceil(waitSeconds * 1000.0d));
    }

    private long retryForWindowReset(TenantPolicy policy, long nowEpochMilli) {
        long windowLength = policy.quotaWindowSeconds() * 1000L;
        return windowLength > 0
                ? Math.max(0, windowStartEpochMilli + windowLength - nowEpochMilli)
                : 0L;
    }

    private long rateRemainingLocked(TenantPolicy policy) {
        return Math.max(0L, (long) Math.floor(Math.min(tokens, policy.rateCapacity())));
    }

    private long quotaRemainingLocked(TenantPolicy policy) {
        return Math.max(0L, policy.quotaLimit() - quotaUsed);
    }

    /**
     * 后台巡检 / 管理端查看时的维护入口：在租户锁内完成与预占/结算路径完全一致的前置维护
     * （终态记录清理 → 到点占用超时释放 → 应用新策略 → 补充令牌 → 窗口滚动与在途占用迁移），
     * 但不做任何速率/配额扣减，因此在租户没有任何业务请求时也能安全调用。
     * 返回本次维护确定性失效并退回占用的记录列表（可能为空），由调用方写审计。
     */
    synchronized List<ReservationRecord> maintain(TenantPolicy policy, long policyVersion,
                                                  long nowEpochMilli) {
        return prepareLocked(policy, policyVersion, nowEpochMilli);
    }

    /**
     * 策略快照暂时不可用时的兜底维护：缺少策略参数故不触碰令牌桶与窗口，
     * 只执行终态清理与到点占用的确定性失效（{@code now >= expiresAt}），
     * 保证“超时自动释放”不依赖策略文件可用。
     */
    synchronized List<ReservationRecord> maintainWithoutPolicy(long nowEpochMilli) {
        return sweepExpired(nowEpochMilli);
    }

    /** 当前配额窗口已用量（含在途占用，管理端查看）。 */
    synchronized long quotaUsed() {
        return quotaUsed;
    }

    /** 当前窗口起始时间（管理端查看）。 */
    synchronized long windowStartEpochMilli() {
        return windowStartEpochMilli;
    }

    /** 当前桶内令牌（管理端查看）。 */
    synchronized double currentTokens() {
        return tokens;
    }

    /** 当前窗口内所有仍有效（ACTIVE 且未超时）占用之和（调用方持锁）。 */
    long activeReservedLocked(long nowEpochMilli) {
        long sum = 0L;
        for (ReservationRecord r : reservations.values()) {
            if (r.status() == ReservationStatus.ACTIVE && nowEpochMilli < r.expiresAtEpochMilli()) {
                sum += r.amount();
            }
        }
        return sum;
    }

    /** 在途（未结算、未超时）预占条数（管理端查看）。 */
    synchronized int activeReservationCount(long nowEpochMilli) {
        int count = 0;
        for (ReservationRecord r : reservations.values()) {
            if (r.status() == ReservationStatus.ACTIVE && nowEpochMilli < r.expiresAtEpochMilli()) {
                count++;
            }
        }
        return count;
    }

    /** 在途占用总量（管理端查看）。 */
    synchronized long activeReservedAmount(long nowEpochMilli) {
        long sum = 0L;
        for (ReservationRecord r : reservations.values()) {
            if (r.status() == ReservationStatus.ACTIVE && nowEpochMilli < r.expiresAtEpochMilli()) {
                sum += r.amount();
            }
        }
        return sum;
    }

    /** 预占键对应记录（管理端/引擎查看，不存在返回 null）。 */
    synchronized ReservationRecord reservation(String key) {
        return reservations.get(key);
    }
}
