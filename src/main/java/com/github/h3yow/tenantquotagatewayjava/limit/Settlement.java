package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 一笔预占的结算结果。重复/乱序/超时/降级等不同结局通过 {@link #status()} 区分，
 * 只有 {@link SettlementStatus#isEffective()} 为 true 的首次结算才真正改变计量。
 *
 * @param requestId        结算请求标识
 * @param tenantId         租户标识
 * @param reservationId    被结算的预占标识
 * @param status           结算结果原因
 * @param reservedAmount   预占用量
 * @param actualAmount     上报的实际用量
 * @param delta            本次相对预占的调整量（正=补扣，负=退回，0/不生效=0）
 * @param settledUsed      该笔业务最终计入窗口的用量
 * @param policyVersion    结算所落窗口对应的策略版本（不可用时为 null）
 * @param quotaRemaining   结算后窗口"可再放行"配额（不适用时为 -1）
 * @param quotaLimit       窗口配额上限（不适用时为 -1）
 * @param detail           人类可读的判定依据
 */
public record Settlement(
        String requestId,
        String tenantId,
        String reservationId,
        SettlementStatus status,
        long reservedAmount,
        long actualAmount,
        long delta,
        long settledUsed,
        Long policyVersion,
        long quotaRemaining,
        long quotaLimit,
        String detail
) {
    public boolean effective() {
        return status.isEffective();
    }
}
