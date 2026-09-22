package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 单个租户的可变计量状态：令牌桶 + 当前配额窗口用量。
 * 状态仅在持有该租户对象内置监视器锁时变更，不同租户使用不同锁，互不阻塞。
 */
final class TenantState {

    /**
     * 一次尝试的内部结果（锁内产生，锁外只读使用）。剩余量在同一把锁内读取，
     * 与本次扣减保持同一时刻的一致性。
     */
    record AcquireOutcome(DecisionReason reason, long retryAfterMillis,
                          long rateRemaining, long quotaRemaining) {
    }

    private final String tenantId;

    // 令牌桶
    private double tokens;
    private long lastRefillEpochMilli;

    // 配额窗口
    private long windowStartEpochMilli;
    private long quotaUsed;

    // 当前状态所对应的策略指纹；策略刷新后据此决定重置规则
    private long appliedPolicyVersion = -1L;
    private long appliedQuotaLimit;
    private long appliedQuotaWindowMillis;

    TenantState(String tenantId, long nowEpochMilli) {
        this.tenantId = tenantId;
        this.lastRefillEpochMilli = nowEpochMilli;
        this.windowStartEpochMilli = nowEpochMilli;
    }

    /** 租户标识。 */
    String tenantId() {
        return tenantId;
    }

    /**
     * 在租户锁内执行一次完整判定（令牌桶 + 配额窗口）：
     * 仅当两者同时可用时才一并扣减，否则都不扣减，不会出现只扣一半。
     *
     * @param policy          当前生效策略
     * @param policyVersion   当前策略版本
     * @param nowEpochMilli   当前时间
     * @return 判定原因与建议重试等待
     */
    synchronized AcquireOutcome tryAcquire(TenantPolicy policy, long policyVersion,
                                           long nowEpochMilli) {
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

        // —— 2. 速率判定（不通过则配额完全不动）——
        if (tokens < 1.0d) {
            long retryAfter = retryForToken(policy);
            return new AcquireOutcome(DecisionReason.RATE_LIMITED, retryAfter,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy));
        }

        // —— 3. 配额窗口滚动 ——
        long windowLength = policy.quotaWindowSeconds() * 1000L;
        if (windowLength > 0 && nowEpochMilli - windowStartEpochMilli >= windowLength) {
            windowStartEpochMilli = nowEpochMilli;
            quotaUsed = 0L;
        }

        // —— 4. 配额判定（不通过则令牌桶的补充结果保留，但不扣令牌）——
        if (quotaUsed >= policy.quotaLimit()) {
            long retryAfter = windowLength > 0
                    ? Math.max(0, windowStartEpochMilli + windowLength - nowEpochMilli)
                    : 0L;
            return new AcquireOutcome(DecisionReason.QUOTA_EXHAUSTED, retryAfter,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy));
        }

        // —— 5. 两者皆可：同一把锁内原子扣减 ——
        tokens -= 1.0d;
        quotaUsed += 1L;
        return new AcquireOutcome(DecisionReason.ALLOWED, 0L,
                rateRemainingLocked(policy), quotaRemainingLocked(policy));
    }

    /**
     * 策略版本变化时的重置规则：
     * 令牌桶按新容量重新充满；配额维度（上限或窗口长度）变化才开新窗口，
     * 否则保留当前窗口已用量——策略刷新不丢失既有用量。
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
            windowStartEpochMilli = nowEpochMilli;
            quotaUsed = 0L;
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

    /** 判定后读取剩余令牌（锁内调用）。 */
    synchronized long rateRemaining(TenantPolicy policy) {
        return rateRemainingLocked(policy);
    }

    /** 判定后读取剩余配额（锁内调用）。 */
    synchronized long quotaRemaining(TenantPolicy policy) {
        return quotaRemainingLocked(policy);
    }

    private long rateRemainingLocked(TenantPolicy policy) {
        return Math.max(0L, (long) Math.floor(Math.min(tokens, policy.rateCapacity())));
    }

    private long quotaRemainingLocked(TenantPolicy policy) {
        return Math.max(0L, policy.quotaLimit() - quotaUsed);
    }

    /** 当前配额窗口已用量（管理端查看）。 */
    synchronized long quotaUsed() {
        return quotaUsed;
    }

    /** 当前配额窗口起始时间（管理端查看）。 */
    synchronized long windowStartEpochMilli() {
        return windowStartEpochMilli;
    }

    /** 当前桶内令牌（管理端查看）。 */
    synchronized double currentTokens() {
        return tokens;
    }
}
