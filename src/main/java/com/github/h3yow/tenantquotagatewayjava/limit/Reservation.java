package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 预占请求的判定结果。
 *
 * @param requestId           请求标识
 * @param tenantId            租户标识（无法识别时为 null）
 * @param reservationId       业务预占标识（同一笔异步业务的预占/结算唯一键）
 * @param reason              判定原因（复用 {@link DecisionReason}）
 * @param estimatedAmount     本次申请预占的预估用量
 * @param policyVersion       判定时使用的策略版本（不可用时为 null）
 * @param rateRemaining       判定后速率桶剩余令牌（不可用时为 -1）
 * @param quotaRemaining      判定后窗口"可再放行"配额（上限-已用-占用，不可用时为 -1）
 * @param quotaLimit          窗口配额上限（不可用时为 -1）
 * @param heldTotal           当前窗口全部在途预占合计
 * @param expiresAtEpochMilli 本预占的失效时刻（未成功预占时为 0）
 * @param retryAfterMillis    建议重试等待毫秒数（不适用时为 0）
 * @param detail              人类可读的判定依据
 */
public record Reservation(
        String requestId,
        String tenantId,
        String reservationId,
        DecisionReason reason,
        long estimatedAmount,
        Long policyVersion,
        long rateRemaining,
        long quotaRemaining,
        long quotaLimit,
        long heldTotal,
        long expiresAtEpochMilli,
        long retryAfterMillis,
        String detail
) {
    public boolean allowed() {
        return reason.isAllowed();
    }
}
