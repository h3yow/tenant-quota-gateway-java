package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 网关对单个请求的判定结果，包含可解释的判定依据。
 *
 * @param requestId       请求标识
 * @param tenantId        租户标识（无法识别时为 null）
 * @param idempotencyKey  幂等键（未提供时为 null）
 * @param reason          判定原因
 * @param policyVersion   判定时使用的策略版本（不可用时为 null）
 * @param rateRemaining   判定后速率桶剩余令牌（不可用时为 -1）
 * @param quotaRemaining  判定后窗口剩余配额（不可用时为 -1）
 * @param quotaLimit      窗口配额上限（不可用时为 -1）
 * @param retryAfterMillis 建议重试等待毫秒数（不适用时为 0）
 * @param detail          人类可读的判定依据
 */
public record Decision(
        String requestId,
        String tenantId,
        String idempotencyKey,
        DecisionReason reason,
        Long policyVersion,
        long rateRemaining,
        long quotaRemaining,
        long quotaLimit,
        long retryAfterMillis,
        String detail
) {
    public boolean allowed() {
        return reason.isAllowed();
    }
}
