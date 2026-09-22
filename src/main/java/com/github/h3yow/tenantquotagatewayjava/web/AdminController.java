package com.github.h3yow.tenantquotagatewayjava.web;

import com.github.h3yow.tenantquotagatewayjava.quota.PolicyRegistry;
import com.github.h3yow.tenantquotagatewayjava.quota.QuotaGatewayService;
import com.github.h3yow.tenantquotagatewayjava.quota.TenantPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理接口：查询/刷新策略、切换配置可用性（用于验证降级链路）。
 */
@RestController
public class AdminController {

    private final QuotaGatewayService service;
    private final PolicyRegistry registry;

    public AdminController(QuotaGatewayService service, PolicyRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    /** 查询当前生效策略快照与版本 */
    @GetMapping("/admin/policies")
    public Map<String, Object> policies() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", registry.version());
        result.put("available", registry.isAvailable());
        result.put("policies", registry.snapshot().values());
        return result;
    }

    /** 以请求体整体替换策略（原子刷新） */
    @PostMapping("/admin/policies/refresh")
    public Map<String, Object> refresh(@RequestBody List<TenantPolicy> policies) {
        long version = service.refreshPolicies(policies);
        return Map.of("refreshed", true, "policyVersion", version, "tenantCount", policies.size());
    }

    /** 切换配置源可用性：POST /admin/config/available?available=false 模拟配置不可用 */
    @PostMapping("/admin/config/available")
    public Map<String, Object> setAvailable(@org.springframework.web.bind.annotation.RequestParam
                                            boolean available) {
        registry.setAvailable(available);
        return Map.of("available", available, "policyVersion", registry.version());
    }
}
