package com.github.h3yow.tenantquotagatewayjava.quota;

/**
 * 本地启动时加载的默认租户策略（便于开箱验证）。
 */
public final class DefaultTenant {

    private DefaultTenant() {
    }

    public static java.util.List<TenantPolicy> policies() {
        return java.util.List.of(
                // tenant-a：2 req/s、突发 3、每 60s 配额 5
                new TenantPolicy("tenant-a", 2.0d, 3L, 5L, 60_000L),
                // tenant-b：1 req/s、突发 1、每 60s 配额 2
                new TenantPolicy("tenant-b", 1.0d, 1L, 2L, 60_000L));
    }
}
