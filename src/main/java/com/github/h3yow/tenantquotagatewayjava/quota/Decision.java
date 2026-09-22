package com.github.h3yow.tenantquotagatewayjava.quota;

/**
 * 一次请求的判定结果。
 *
 * @param allowed       是否放行
 * @param reason        拒绝/降级原因（放行且无降级时为 {@link RejectionReason#NONE}）
 * @param detail        判定依据的可读描述
 * @param tenantId      租户标识
 * @param requestId     请求标识（幂等键）
 * @param policyVersion 判定所依据的策略版本
 */
public record Decision(boolean allowed,
                       RejectionReason reason,
                       String detail,
                       String tenantId,
                       String requestId,
                       long policyVersion) {

    public static Decision allow(String tenantId, String requestId, long policyVersion, String detail) {
        return new Decision(true, RejectionReason.NONE, detail, tenantId, requestId, policyVersion);
    }

    public static Decision reject(RejectionReason reason, String tenantId, String requestId,
                                  long policyVersion, String detail) {
        return new Decision(false, reason, detail, tenantId, requestId, policyVersion);
    }
}
