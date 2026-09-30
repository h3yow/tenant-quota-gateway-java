package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 单个租户持有的一笔预占的可变记录。仅在持有该租户对象监视器锁时访问与变更。
 *
 * <p>记录在预占创建时钉住其所属配额窗口的起始时间、策略版本与上限：后续窗口滚动或
 * 配额维度刷新后到达的结算会落到"正确的旧窗口"，不影响当前窗口；同维度刷新则继续
 * 在当前窗口内退补。</p>
 */
final class ReservationRecord {

    /** 预占生命周期状态。 */
    enum Status {
        /** 已预占、等待结算；额度被占用。 */
        HELD,
        /** 已正常结算；保留结论用于重复结算去重。 */
        SETTLED,
        /** 已超时失效；占用已释放，迟到结算不生效。 */
        EXPIRED
    }

    private final String reservationId;
    private final long amount;
    private final long createdAtEpochMilli;
    private final long expiresAtEpochMilli;
    private final long windowStartEpochMilli;
    private final long windowPolicyVersion;
    private final long quotaLimit;
    private final String firstRequestId;

    private Status status = Status.HELD;
    private long actualAmount;
    private SettlementStatus settlementStatus;
    private long settledUsed;
    private long settledDelta;
    private String settledBasis;
    private long finishedAtEpochMilli;

    ReservationRecord(String reservationId, long amount, long createdAtEpochMilli,
                      long ttlMillis, long windowStartEpochMilli, long windowPolicyVersion,
                      long quotaLimit, String firstRequestId) {
        this.reservationId = reservationId;
        this.amount = amount;
        this.createdAtEpochMilli = createdAtEpochMilli;
        this.expiresAtEpochMilli = createdAtEpochMilli + ttlMillis;
        this.windowStartEpochMilli = windowStartEpochMilli;
        this.windowPolicyVersion = windowPolicyVersion;
        this.quotaLimit = quotaLimit;
        this.firstRequestId = firstRequestId;
    }

    String reservationId() {
        return reservationId;
    }

    long amount() {
        return amount;
    }

    long createdAtEpochMilli() {
        return createdAtEpochMilli;
    }

    long expiresAtEpochMilli() {
        return expiresAtEpochMilli;
    }

    long windowStartEpochMilli() {
        return windowStartEpochMilli;
    }

    long windowPolicyVersion() {
        return windowPolicyVersion;
    }

    long quotaLimit() {
        return quotaLimit;
    }

    String firstRequestId() {
        return firstRequestId;
    }

    Status status() {
        return status;
    }

    boolean isHeld() {
        return status == Status.HELD;
    }

    boolean expiredAt(long nowEpochMilli) {
        return nowEpochMilli >= expiresAtEpochMilli;
    }

    /** 记录保持期（结算/失效结论）是否已过，可从映射中物理清除。 */
    boolean removableAt(long nowEpochMilli, long recordTtlMillis) {
        if (status == Status.HELD) {
            return false;
        }
        return finishedAtEpochMilli > 0 && nowEpochMilli - finishedAtEpochMilli >= recordTtlMillis;
    }

    long actualAmount() {
        return actualAmount;
    }

    SettlementStatus settlementStatus() {
        return settlementStatus;
    }

    long settledUsed() {
        return settledUsed;
    }

    long settledDelta() {
        return settledDelta;
    }

    String settledBasis() {
        return settledBasis;
    }

    /** 标记为已结算（锁内调用）。 */
    void markSettled(long actual, SettlementStatus result, long used, long delta,
                    long nowEpochMilli, String basis) {
        this.status = Status.SETTLED;
        this.actualAmount = actual;
        this.settlementStatus = result;
        this.settledUsed = used;
        this.settledDelta = delta;
        this.settledBasis = basis;
        this.finishedAtEpochMilli = nowEpochMilli;
    }

    /** 标记为超时失效（锁内调用）。 */
    void markExpired(long nowEpochMilli) {
        this.status = Status.EXPIRED;
        this.finishedAtEpochMilli = nowEpochMilli;
    }
}
