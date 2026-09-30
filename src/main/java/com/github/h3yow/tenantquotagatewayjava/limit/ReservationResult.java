package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 网关对一次「预占」或「结算」操作的结果，包含可解释的判定依据。
 *
 * @param requestId       本次操作的请求标识
 * @param tenantId        租户标识（无法识别时为 null）
 * @param reservationKey  预占键（业务幂等键）
 * @param reason          判定/拒绝原因（决定 HTTP 状态码）
 * @param status          该预占键当前所处的生命周期状态（无法判定时为 null）
 * @param policyVersion   判定时使用的策略版本（不可用时为 null）
 * @param rateRemaining   判定后速率桶剩余令牌（不可用时为 -1）
 * @param quotaRemaining  判定后窗口剩余可分配配额（已扣除全部有效占用，不可用时为 -1）
 * @param quotaLimit      窗口配额上限（不可用时为 -1）
 * @param estimated       本次请求的预估用量（结算时为真实用量；不适用时为 0）
 * @param reservedAmount  当前记录上的预占量（已结算/过期后仍保留原值；不适用时为 0）
 * @param settledAmount   已结算的真实用量（未结算时为 0）
 * @param retryAfterMillis 建议重试等待毫秒数（不适用时为 0）
 * @param detail          人类可读的判定依据
 */
public record ReservationResult(
        String requestId,
        String tenantId,
        String reservationKey,
        DecisionReason reason,
        ReservationStatus status,
        Long policyVersion,
        long rateRemaining,
        long quotaRemaining,
        long quotaLimit,
        long estimated,
        long reservedAmount,
        long settledAmount,
        long retryAfterMillis,
        String detail
) {
    public boolean allowed() {
        return reason.isAllowed();
    }
}
