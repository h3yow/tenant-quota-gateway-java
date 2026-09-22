package com.github.h3yow.tenantquotagatewayjava.config;

import com.github.h3yow.tenantquotagatewayjava.quota.AuditLog;
import com.github.h3yow.tenantquotagatewayjava.quota.PolicyRegistry;
import com.github.h3yow.tenantquotagatewayjava.quota.QuotaGatewayService;
import com.github.h3yow.tenantquotagatewayjava.quota.QuotaLedger;
import com.github.h3yow.tenantquotagatewayjava.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配限流/配额核心组件。
 */
@Configuration
public class GatewayBeans {

    @Bean
    public QuotaLedger quotaLedger() {
        return new QuotaLedger();
    }

    @Bean
    public PolicyRegistry policyRegistry(AuditLog auditLog) {
        return new PolicyRegistry(auditLog);
    }

    @Bean
    public QuotaGatewayService quotaGatewayService(PolicyRegistry registry,
                                                   QuotaLedger ledger,
                                                   AuditLog auditLog,
                                                   GatewayProperties properties) {
        return new QuotaGatewayService(registry, ledger, auditLog, properties);
    }
}
