package com.github.h3yow.tenantquotagatewayjava.config;

import com.github.h3yow.tenantquotagatewayjava.limit.Clock;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.PolicyProvider;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.ReservationReaper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关 Bean 装配。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    @Bean
    public Clock clock() {
        return Clock.system();
    }

    @Bean
    public DefaultAuditLogger auditLogger(Clock clock, GatewayProperties properties) {
        return new DefaultAuditLogger(clock, properties.getAuditBufferSize());
    }

    @Bean
    public PolicyProvider policyProvider(org.springframework.core.io.ResourceLoader resourceLoader,
                                         GatewayProperties properties, Clock clock) {
        return new JsonPolicyProvider(resourceLoader, properties.getPolicyLocation(), clock);
    }

    @Bean
    public QuotaLimitEngine quotaLimitEngine(PolicyProvider policyProvider,
                                             DefaultAuditLogger auditLogger, Clock clock,
                                             GatewayProperties properties) {
        return new QuotaLimitEngine(policyProvider, auditLogger, clock,
                properties.isFailOpen(), properties.getIdempotencyTtlMillis(),
                properties.getReservationTtlMillis(),
                properties.getReservationRecordTtlMillis());
    }

    @Bean
    public ReservationReaper reservationReaper(QuotaLimitEngine engine, Clock clock,
                                               GatewayProperties properties) {
        return new ReservationReaper(engine, clock,
                properties.getReservationSweepIntervalMillis());
    }
}
