package com.github.h3yow.tenantquotagatewayjava.quota;

/**
 * 请求被拒绝（或降级放行）的原因分类。
 */
public enum RejectionReason {
    /** 未拒绝 */
    NONE,
    /** 无法识别租户（缺少租户标识） */
    TENANT_NOT_IDENTIFIED,
    /** 租户未配置任何策略 */
    TENANT_NOT_CONFIGURED,
    /** 超出租户速率限制 */
    RATE_LIMIT_EXCEEDED,
    /** 超出租户配额 */
    QUOTA_EXCEEDED,
    /** 策略不可用，按 FAIL_CLOSED 语义拒绝 */
    POLICY_UNAVAILABLE_REJECTED,
    /** 策略不可用，按 FAIL_OPEN 语义放行（非拒绝，仅标记） */
    POLICY_UNAVAILABLE_ALLOWED
}
