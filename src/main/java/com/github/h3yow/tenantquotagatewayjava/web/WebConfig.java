package com.github.h3yow.tenantquotagatewayjava.web;

import com.github.h3yow.tenantquotagatewayjava.config.GatewayProperties;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web 层装配：仅对 /api/** 注册限流过滤器，管理端接口不参与限流。
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<QuotaLimitFilter> quotaLimitFilterRegistration(
            QuotaLimitEngine engine, GatewayProperties properties) {
        FilterRegistrationBean<QuotaLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new QuotaLimitFilter(engine, properties));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
