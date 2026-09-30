package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 单笔配额预占的记录（状态机）。记录本身仅保存数据，所有状态迁移都必须在所属租户的
 * {@link TenantState} 监视器锁内发生，从而与窗口用量的变更严格原子。
 *
 * <p>生命周期：{@link ReservationStatus#ACTIVE} →（结算）{@link ReservationStatus#SETTLED}
 * 或（超时）{@link ReservationStatus#EXPIRED}，二者均为终态。</p>
 */
final class ReservationRecord {

    private final String key;
    private final String reserveRequestId;
    private final long amount;
    private final long reservedAtEpochMilli;
    private final long expiresAtEpochMilli;
    private long windowStartEpochMilli;
    private final long policyVersion;

    private ReservationStatus status = ReservationStatus.ACTIVE;
    private long settledAmount;
    private String settleRequestId;
    private long settledAtEpochMilli;

    ReservationRecord(String key, String reserveRequestId, long amount,
                      long reservedAtEpochMilli, long ttlMillis,
                      long windowStartEpochMilli, long policyVersion) {
        this.key = key;
        this.reserveRequestId = reserveRequestId;
        this.amount = amount;
        this.reservedAtEpochMilli = reservedAtEpochMilli;
        this.expiresAtEpochMilli = reservedAtEpochMilli + Math.max(1L, ttlMillis);
        this.windowStartEpochMilli = windowStartEpochMilli;
        this.policyVersion = policyVersion;
    }

    String key() { return key; }
    String reserveRequestId() { return reserveRequestId; }
    long amount() { return amount; }
    long reservedAtEpochMilli() { return reservedAtEpochMilli; }
    long expiresAtEpochMilli() { return expiresAtEpochMilli; }
    long windowStartEpochMilli() { return windowStartEpochMilli; }
    long policyVersion() { return policyVersion; }

    /**
     * 窗口自然滚动或配额维度刷新时，把仍在途的占用改绑到新窗口起点，
     * 保证占用不凭空消失、且后续结算落到当前窗口。仅允许在租户锁内对 ACTIVE 记录调用。
     */
    void rebindWindow(long newWindowStartEpochMilli) {
        this.windowStartEpochMilli = newWindowStartEpochMilli;
    }
    ReservationStatus status() { return status; }
    long settledAmount() { return settledAmount; }
    String settleRequestId() { return settleRequestId; }
    long settledAtEpochMilli() { return settledAtEpochMilli; }

    boolean activeAt(long nowEpochMilli) {
        return status == ReservationStatus.ACTIVE && nowEpochMilli < expiresAtEpochMilli;
    }

    boolean expiredAt(long nowEpochMilli) {
        return status == ReservationStatus.ACTIVE && nowEpochMilli >= expiresAtEpochMilli;
    }

    void markExpired(long nowEpochMilli) {
        status = ReservationStatus.EXPIRED;
        settledAtEpochMilli = nowEpochMilli;
    }

    void markSettled(long actualAmount, String requestId, long nowEpochMilli) {
        status = ReservationStatus.SETTLED;
        this.settledAmount = actualAmount;
        this.settleRequestId = requestId;
        this.settledAtEpochMilli = nowEpochMilli;
    }

    /** 终态记录可被清理的最早时间（结算/过期后再保留 terminalTtlMillis）。 */
    long purgeableAt(long terminalTtlMillis) {
        return settledAtEpochMilli + terminalTtlMillis;
    }
}
