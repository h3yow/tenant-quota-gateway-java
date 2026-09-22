package com.github.h3yow.tenantquotagatewayjava.quota;

/**
 * 配额计量结果。
 */
public enum LedgerResult {
    /** 首次提交：用量已扣减 */
    CONSUMED,
    /** 重复提交（幂等命中）：不再扣减 */
    DUPLICATE,
    /** 配额已满：拒绝，不扣减 */
    EXCEEDED
}
