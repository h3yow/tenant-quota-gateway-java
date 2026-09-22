package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 判定/拒绝原因。不同原因可区分，同时决定返回给客户端的 HTTP 状态码。
 */
public enum DecisionReason {

    /** 请求被允许，成功扣减速率令牌与配额。 */
    ALLOWED(true, 200),

    /** 请求被允许，但命中幂等键缓存，属于重试/重复提交，未再次扣减用量。 */
    IDEMPOTENT_REPLAY(true, 200),

    /** 请求缺少租户标识。 */
    MISSING_TENANT(false, 400),

    /** 租户没有任何生效策略（未配置）。 */
    UNKNOWN_TENANT(false, 403),

    /** 超出速率限制（令牌桶突发容量不足）。 */
    RATE_LIMITED(false, 429),

    /** 超出周期配额（窗口内可用额度不足）。 */
    QUOTA_EXHAUSTED(false, 429),

    /** 降级策略为 fail-closed，因内部状态/配置不可用而拒绝。 */
    DEGRADED_DENY(false, 503);

    private final boolean allowed;
    private final int httpStatus;

    DecisionReason(boolean allowed, int httpStatus) {
        this.allowed = allowed;
        this.httpStatus = httpStatus;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
