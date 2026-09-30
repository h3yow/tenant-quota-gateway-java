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
    DEGRADED_DENY(false, 503),

    /** 预占成功：已按预估用量占用配额（同时扣减 1 个速率令牌）。 */
    RESERVED(true, 200),

    /** 结算成功：已按真实用量多退少补。 */
    SETTLED(true, 200),

    /** 预占/结算的幂等重放：同一预占键的重复提交，未重复占用或计费。 */
    RESERVATION_REPLAY(true, 200),

    /** 请求参数非法（如预估/真实用量小于 1）。 */
    INVALID_ARGUMENT(false, 400),

    /** 结算引用了不存在的预占键（从未成功预占，或记录已过保留期清理）。 */
    RESERVATION_NOT_FOUND(false, 404),

    /** 预占已超时自动释放，迟到的结算不再生效（不能对失效占用计费）。 */
    RESERVATION_EXPIRED(false, 409),

    /** 预占键已处于终态（已结算或已过期），不能再用它发起新预占。 */
    RESERVATION_CLOSED(false, 409);

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
