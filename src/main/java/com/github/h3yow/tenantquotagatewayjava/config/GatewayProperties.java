package com.github.h3yow.tenantquotagatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关可配置项（前缀 gateway）。
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 降级模式：FAIL_OPEN（放行）或 FAIL_CLOSED（拒绝） */
    private String degradationMode = "FAIL_CLOSED";

    /** 未配置策略的租户如何处理：REJECT 或 ALLOW */
    private String unknownTenantMode = "REJECT";

    public String getDegradationMode() {
        return degradationMode;
    }

    public void setDegradationMode(String degradationMode) {
        this.degradationMode = degradationMode;
    }

    public String getUnknownTenantMode() {
        return unknownTenantMode;
    }

    public void setUnknownTenantMode(String unknownTenantMode) {
        this.unknownTenantMode = unknownTenantMode;
    }
}
