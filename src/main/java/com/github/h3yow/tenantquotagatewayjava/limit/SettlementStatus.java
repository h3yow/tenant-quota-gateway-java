package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 一笔预占结算的处理结果。每种结果原因可区分，{@link #effective} 表示本次调用是否
 * 真正改变了计量（重复结算/超时结算等不生效的调用为 false）。
 */
public enum SettlementStatus {

    /** 结算生效：实际用量等于预占，无需退补。 */
    SETTLED_EXACT(true, 200),
    /** 结算生效：实际用量少于预占，差额已退回。 */
    SETTLED_REFUNDED(true, 200),
    /** 结算生效：实际用量多于预占，差额已补扣。 */
    SETTLED_SUPPLEMENTED(true, 200),
    /** 结算生效：实际用量超过窗口上限，已按实际用量记账并标记超用。 */
    SETTLED_OVER_LIMIT(true, 200),
    /** 结算生效一次：预占窗口已滚动/配额维度已变更，仅做记账，不影响当前窗口。 */
    SETTLED_CLOSED_WINDOW(true, 200),

    /** 重复结算：该预占此前已结算，返回首次结论，计量未再次改变。 */
    SETTLEMENT_DUPLICATE(false, 200),
    /** 预占已超时失效，迟到的结算不生效、不计费。 */
    SETTLEMENT_EXPIRED(false, 409),
    /** 预占不存在（从未创建或记录已过保留期）。 */
    SETTLEMENT_NOT_FOUND(false, 404),
    /** 请求参数非法（如实际用量为负、预占标识缺失）。 */
    SETTLEMENT_INVALID(false, 400),
    /** 内部状态/配置不可用，按降级策略处理，本次结算不改变计量。 */
    SETTLEMENT_DEGRADED(false, 503);

    private final boolean effective;
    private final int httpStatus;

    SettlementStatus(boolean effective, int httpStatus) {
        this.effective = effective;
        this.httpStatus = httpStatus;
    }

    public boolean isEffective() {
        return effective;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
