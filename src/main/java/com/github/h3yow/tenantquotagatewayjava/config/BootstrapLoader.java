package com.github.h3yow.tenantquotagatewayjava.config;

import com.github.h3yow.tenantquotagatewayjava.quota.DefaultTenant;
import com.github.h3yow.tenantquotagatewayjava.quota.QuotaGatewayService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时加载默认租户策略，使应用开箱即可验证。
 */
@Component
public class BootstrapLoader implements ApplicationRunner {

    private final QuotaGatewayService service;

    public BootstrapLoader(QuotaGatewayService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.refreshPolicies(DefaultTenant.policies());
    }
}
