package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 审计日志：记录拒绝、幂等重放、策略变更与降级事件。
 */
public interface AuditLogger {

    void log(AuditEvent event);
}
