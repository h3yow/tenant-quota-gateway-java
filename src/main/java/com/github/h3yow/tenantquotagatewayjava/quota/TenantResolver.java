package com.github.h3yow.tenantquotagatewayjava.quota;

import org.springframework.stereotype.Component;

/**
 * 租户识别器：从请求头 X-Tenant-Id 识别租户；缺失或空白视为无法识别。
 * X-Request-Id 作为业务请求的幂等键（重复提交/重试复用同一值）。
 */
@Component
public class TenantResolver {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * @return 规范化后的租户标识；无法识别时返回 null
     */
    public String resolve(String tenantHeader, String requestIdHeader) {
        if (tenantHeader == null) {
            return null;
        }
        String trimmed = tenantHeader.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
