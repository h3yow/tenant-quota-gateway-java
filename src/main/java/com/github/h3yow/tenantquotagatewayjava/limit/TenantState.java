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

    /** 预占尝试的内部结果（锁内产生）。 */
    record ReserveOutcome(DecisionReason reason, long retryAfterMillis, long rateRemaining,
                          long quotaRemaining, long heldTotal, long expiresAtEpochMilli,
                          boolean duplicate, String basis) {
    }

    /** 结算尝试的内部结果（锁内产生）。 */
    record SettleOutcome(SettlementStatus status, long reservedAmount, long delta,
                         long settledUsed, Long windowPolicyVersion, long quotaRemaining,
                         long quotaLimit, String basis) {
    }

    /** 预占记录映射（含已结算/已失效记录用于去重，惰性清理）。 */
    private final java.util.LinkedHashMap<String, ReservationRecord> reservations =
            new java.util.LinkedHashMap<>();

    /** 当前窗口内处于 HELD 的预占合计；窗口滚动/配额维度变更时清零。 */
    private long quotaHeld;

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

        // —— 3. 配额窗口滚动（形状变化时在 applyNewPolicy 内处理）——
        rollWindowIfNeeded(policy, nowEpochMilli);
        // 普通判定路径同样惰性释放超时占用，保证不主动扫描也不会漏判
        expireHeldLocked(nowEpochMilli);

        // —— 4. 配额判定：被在途预占占住的额度不能被普通请求复用 ——
        if (quotaUsed + quotaHeld >= policy.quotaLimit()) {
            long windowLengthAfterRoll = policy.quotaWindowSeconds() * 1000L;
            long retryAfter = windowLengthAfterRoll > 0
                    ? Math.max(0, windowStartEpochMilli + windowLengthAfterRoll - nowEpochMilli)
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
     * 普通窗口滚动：形状未变但当前窗口已结束时清零并开新窗口。形状变化由
     * {@link #applyNewPolicy} 处理（其同样会清零在途占用合计）。
     */
    private void rollWindowIfNeeded(TenantPolicy policy, long nowEpochMilli) {
        long windowLength = policy.quotaWindowSeconds() * 1000L;
        if (windowLength > 0 && nowEpochMilli - windowStartEpochMilli >= windowLength) {
            windowStartEpochMilli = nowEpochMilli;
            quotaUsed = 0L;
            quotaHeld = 0L;
        }
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
            // 旧窗口/旧维度的在途占用不再计入新窗口；其记录保留，迟到结算走"关闭窗口"记账
            quotaHeld = 0L;
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
        // 可再放行额度 = 上限 - 已用 - 在途占用（被占住的部分不能重复使用）
        return Math.max(0L, policy.quotaLimit() - quotaUsed - quotaHeld);
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

    // ===== 预占 / 结算（先占用、后结算） =====

    /**
     * 尝试预占 estimatedAmount 个配额（速率闸门照常扣 1 令牌）。同一把租户锁内完成
     * 惰性超时释放、重复预占识别、可用额度检查与占用登记，因此并发放行总量不会超过上限。
     */
    synchronized ReserveOutcome tryReserve(TenantPolicy policy, long policyVersion,
                                           long nowEpochMilli, String reservationId,
                                           String firstRequestId, long estimatedAmount,
                                           long ttlMillis, long recordTtlMillis) {
        if (policyVersion != appliedPolicyVersion) {
            applyNewPolicy(policy, policyVersion, nowEpochMilli);
        }

        // 令牌桶补充与速率闸门（预占请求本身仍受速率限制）
        long elapsed = nowEpochMilli - lastRefillEpochMilli;
        if (elapsed > 0) {
            tokens = Math.min(policy.rateCapacity(),
                    tokens + (elapsed / 1000.0) * policy.rateRefillPerSecond());
            lastRefillEpochMilli = nowEpochMilli;
        }

        rollWindowIfNeeded(policy, nowEpochMilli);
        cleanupLocked(nowEpochMilli, recordTtlMillis);

        ReservationRecord existing = reservations.get(reservationId);
        if (existing != null) {
            if (existing.isHeld() && !existing.expiredAt(nowEpochMilli)) {
                return new ReserveOutcome(DecisionReason.IDEMPOTENT_REPLAY, 0L,
                        rateRemainingLocked(policy), quotaRemainingLocked(policy),
                        quotaHeld, existing.expiresAtEpochMilli(), true,
                        "预占重放: reservationId=" + reservationId + " 首次请求 requestId="
                                + existing.firstRequestId() + " 已占用 " + existing.amount()
                                + "，未重复占用额度");
            }
            if (existing.isHeld()) {
                // 已超时的 HELD 在 cleanup 中已被释放并转为 EXPIRED
                return new ReserveOutcome(DecisionReason.INVALID_REQUEST, 0L,
                        rateRemainingLocked(policy), quotaRemainingLocked(policy), quotaHeld,
                        0L, false,
                        "reservationId=" + reservationId + " 已超时失效，不能重复预占，"
                                + "请使用新的预占标识");
            }
            // 已结算/已失效的标识不允许复用
            return new ReserveOutcome(DecisionReason.INVALID_REQUEST, 0L,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy), quotaHeld, 0L,
                    false,
                    "reservationId=" + reservationId + " 已处于终态(" + existing.status()
                            + ")，请使用新的预占标识");
        }

        if (tokens < 1.0d) {
            return new ReserveOutcome(DecisionReason.RATE_LIMITED, retryForToken(policy),
                    rateRemainingLocked(policy), quotaRemainingLocked(policy), quotaHeld, 0L,
                    false,
                    "预占被速率闸门拒绝: 剩余令牌=" + rateRemainingLocked(policy) + ", 容量="
                            + policy.rateCapacity());
        }

        if (estimatedAmount < 1L) {
            return new ReserveOutcome(DecisionReason.INVALID_REQUEST, 0L,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy), quotaHeld, 0L,
                    false, "预估用量必须 >= 1，收到 " + estimatedAmount);
        }

        long windowLength = policy.quotaWindowSeconds() * 1000L;
        if (quotaUsed + quotaHeld + estimatedAmount > policy.quotaLimit()) {
            long retryAfter = windowLength > 0
                    ? Math.max(0, windowStartEpochMilli + windowLength - nowEpochMilli)
                    : 0L;
            return new ReserveOutcome(DecisionReason.QUOTA_EXHAUSTED, retryAfter,
                    rateRemainingLocked(policy), quotaRemainingLocked(policy), quotaHeld, 0L,
                    false,
                    "预占被配额闸门拒绝: 已用=" + quotaUsed + " 在途占用=" + quotaHeld
                            + " 本次申请=" + estimatedAmount + " 上限=" + policy.quotaLimit()
                            + "，放行将超过上限; 窗口重置建议等待=" + retryAfter + "ms");
        }

        tokens -= 1.0d;
        quotaHeld += estimatedAmount;
        long expiresAt = nowEpochMilli + ttlMillis;
        ReservationRecord record = new ReservationRecord(reservationId, estimatedAmount,
                nowEpochMilli, ttlMillis, windowStartEpochMilli, policyVersion,
                policy.quotaLimit(), firstRequestId);
        reservations.put(reservationId, record);
        return new ReserveOutcome(DecisionReason.ALLOWED, 0L, rateRemainingLocked(policy),
                quotaRemainingLocked(policy), quotaHeld, expiresAt, false,
                "已在租户锁内占用 " + estimatedAmount + " 配额并扣减1令牌: 已用=" + quotaUsed
                        + " 在途占用=" + quotaHeld + " 上限=" + policy.quotaLimit()
                        + " 可再放行=" + quotaRemainingLocked(policy) + " 预占将于 "
                        + ttlMillis + "ms 后超时失效");
    }


    /**
     * 对一笔预占按实际用量结算。锁内完成：重复结算返回首次结论；超时/窗口滚动/维度变更
     * 有各自明确的结局；同一窗口内退补原子生效，全程只可能真正生效一次。
     */
    synchronized SettleOutcome settle(TenantPolicy policy, long policyVersion,
                                     long nowEpochMilli, String reservationId,
                                     long actualAmount, long recordTtlMillis) {
        if (policyVersion != appliedPolicyVersion) {
            applyNewPolicy(policy, policyVersion, nowEpochMilli);
        }
        rollWindowIfNeeded(policy, nowEpochMilli);
        cleanupLocked(nowEpochMilli, recordTtlMillis);

        if (actualAmount < 0L) {
            return new SettleOutcome(SettlementStatus.SETTLEMENT_INVALID, 0L, 0L, 0L,
                    policyVersion, quotaRemainingLocked(policy), policy.quotaLimit(),
                    "实际用量不能为负，收到 " + actualAmount);
        }

        ReservationRecord record = reservations.get(reservationId);
        if (record == null) {
            return new SettleOutcome(SettlementStatus.SETTLEMENT_NOT_FOUND, 0L, 0L, 0L,
                    policyVersion, quotaRemainingLocked(policy), policy.quotaLimit(),
                    "预占不存在: reservationId=" + reservationId
                            + "（从未创建或记录已过保留期），未发生任何计费");
        }

        if (record.status() == ReservationRecord.Status.SETTLED) {
            return duplicateOutcome(record, policy);
        }
        if (record.status() == ReservationRecord.Status.EXPIRED) {
            return new SettleOutcome(SettlementStatus.SETTLEMENT_EXPIRED, record.amount(), 0L,
                    0L, record.windowPolicyVersion(), -1L, record.quotaLimit(),
                    "迟到结算被拒绝: reservationId=" + reservationId + " 的预占已于超时后失效，"
                            + "占用 " + record.amount() + " 已释放，本次不计费");
        }

        // HELD：超时判定优先——占用失效后不能再被当成有效额度结算
        if (record.expiredAt(nowEpochMilli)) {
            releaseHeldLocked(record);
            record.markExpired(nowEpochMilli);
            return new SettleOutcome(SettlementStatus.SETTLEMENT_EXPIRED, record.amount(), 0L,
                    0L, record.windowPolicyVersion(),
                    remainingIfCurrentWindow(record, policy),
                    record.quotaLimit(),
                    "迟到结算被拒绝: reservationId=" + reservationId + " 的预占已于 "
                            + record.expiresAtEpochMilli() + " 超时失效，占用 "
                            + record.amount() + " 已释放，本次不计费");
        }

        // 未超时但所属窗口已滚动/维度变更 → 落到正确的旧窗口记账，不影响当前窗口
        if (record.windowStartEpochMilli() != windowStartEpochMilli) {
            long delta = actualAmount - record.amount();
            String action = delta < 0L ? "退回 " + (-delta)
                    : delta > 0L ? "补扣 " + delta : "无退补";
            String basis = "结算落到预占所属的旧窗口(v" + record.windowPolicyVersion()
                    + ", 窗口起点 " + record.windowStartEpochMilli() + ", 上限 "
                    + record.quotaLimit() + "): 预占 " + record.amount() + " 实际 "
                    + actualAmount + "，" + action
                    + "；当前窗口已不同，仅在旧窗口记账一次，不改变当前窗口用量与占用";
            record.markSettled(actualAmount, SettlementStatus.SETTLED_CLOSED_WINDOW,
                    actualAmount, delta, nowEpochMilli, basis);
            return new SettleOutcome(SettlementStatus.SETTLED_CLOSED_WINDOW, record.amount(),
                    delta, actualAmount, record.windowPolicyVersion(),
                    quotaRemainingLocked(policy), policy.quotaLimit(), basis);
        }

        // 到达此处说明预占仍属于当前窗口：释放占用，按实际用量在同一窗口记账
        long reserved = record.amount();
        quotaHeld -= reserved;
        if (quotaHeld < 0L) {
            quotaHeld = 0L;
        }
        quotaUsed += actualAmount;
        long delta = actualAmount - reserved;
        SettlementStatus status;
        if (quotaUsed > policy.quotaLimit()) {
            status = SettlementStatus.SETTLED_OVER_LIMIT;
        } else if (delta < 0L) {
            status = SettlementStatus.SETTLED_REFUNDED;
        } else if (delta > 0L) {
            status = SettlementStatus.SETTLED_SUPPLEMENTED;
        } else {
            status = SettlementStatus.SETTLED_EXACT;
        }
        String action = delta < 0L ? "退回 " + (-delta)
                : delta > 0L ? "补扣 " + delta : "无退补";
        String basis = "结算在预占所属窗口(v" + policyVersion + ")内原子生效: 预占 " + reserved
                + " 实际 " + actualAmount + "，" + action + "；结算后已用=" + quotaUsed
                + " 在途占用=" + quotaHeld + " 上限=" + policy.quotaLimit()
                + (status == SettlementStatus.SETTLED_OVER_LIMIT
                ? "；实际用量已超过窗口上限，按真实用量记账并标记超用" : "");
        record.markSettled(actualAmount, status, actualAmount, delta, nowEpochMilli, basis);
        return new SettleOutcome(status, reserved, delta, actualAmount, policyVersion,
                quotaRemainingLocked(policy), policy.quotaLimit(), basis);
    }

    private SettleOutcome duplicateOutcome(ReservationRecord record, TenantPolicy policy) {
        boolean sameWindow = record.windowStartEpochMilli() == windowStartEpochMilli;
        long remaining = sameWindow ? quotaRemainingLocked(policy) : -1L;
        String basis = "重复结算被幂等拦截: reservationId=" + record.reservationId()
                + " 首次结算已生效(" + record.settlementStatus() + ", 预占 "
                + record.amount() + " 实际 " + record.actualAmount() + ")，本次返回首次结论，"
                + "计量未再次改变";
        return new SettleOutcome(SettlementStatus.SETTLEMENT_DUPLICATE, record.amount(),
                record.settledDelta(), record.settledUsed(), record.windowPolicyVersion(),
                remaining, record.quotaLimit(), basis);
    }

    private long remainingIfCurrentWindow(ReservationRecord record, TenantPolicy policy) {
        return record.windowStartEpochMilli() == windowStartEpochMilli
                ? quotaRemainingLocked(policy) : -1L;
    }

    /**
     * 将到达失效时刻的 HELD 预占转为 EXPIRED（锁内）。仅当仍属于当前窗口时才从占用
     * 合计释放（窗口滚动时合计已整体清零，旧窗口记录保留至 TTL 供"关闭窗口结算"）。
     */
    private void expireHeldLocked(long nowEpochMilli) {
        for (ReservationRecord r : reservations.values()) {
            if (r.isHeld() && r.expiredAt(nowEpochMilli)) {
                releaseHeldLocked(r);
                r.markExpired(nowEpochMilli);
            }
        }
    }

    /**
     * 惰性维护（锁内）：超时 HELD 转 EXPIRED；已结束记录超过保留期则物理移除。
     */
    private void cleanupLocked(long nowEpochMilli, long recordTtlMillis) {
        expireHeldLocked(nowEpochMilli);
        reservations.values().removeIf(
                r -> !r.isHeld() && r.removableAt(nowEpochMilli, recordTtlMillis));
    }

    /** 仅当记录仍属于当前窗口时，其占用才包含在 quotaHeld 中，才可释放。 */
    private void releaseHeldLocked(ReservationRecord r) {
        if (r.windowStartEpochMilli() == windowStartEpochMilli) {
            quotaHeld -= r.amount();
            if (quotaHeld < 0L) {
                quotaHeld = 0L;
            }
        }
    }

    /**
     * 后台/管理端触发：扫描并释放当前窗口内已超时的在途预占。返回被释放记录（锁外用于审计）。
     */
    synchronized java.util.List<ReservationRecord> sweepExpired(long nowEpochMilli,
                                                                long recordTtlMillis) {
        java.util.List<ReservationRecord> expired = new java.util.ArrayList<>();
        var it = reservations.entrySet().iterator();
        while (it.hasNext()) {
            ReservationRecord r = it.next().getValue();
            if (r.isHeld() && r.expiredAt(nowEpochMilli)) {
                releaseHeldLocked(r);
                r.markExpired(nowEpochMilli);
                expired.add(r);
            } else if (!r.isHeld() && r.removableAt(nowEpochMilli, recordTtlMillis)) {
                it.remove();
            }
        }
        return expired;
    }

    /** 当前窗口在途预占合计（管理端查看）。 */
    synchronized long quotaHeld() {
        return quotaHeld;
    }

    /** 当前预占记录条数（含已结算/已失效，惰性清理前，管理端查看）。 */
    synchronized int reservationCount() {
        return reservations.size();
    }
}
