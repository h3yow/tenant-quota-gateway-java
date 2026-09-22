package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 单个租户的限流/配额策略。
 *
 * @param tenantId            租户标识
 * @param rateCapacity        令牌桶容量（允许的突发请求数）
 * @param rateRefillPerSecond 令牌桶每秒补充速率（持续速率）
 * @param quotaLimit          每个配额窗口的总用量上限
 * @param quotaWindowSeconds  配额窗口长度（秒）
 */
public record TenantPolicy(
        String tenantId,
        long rateCapacity,
        double rateRefillPerSecond,
        long quotaLimit,
        long quotaWindowSeconds
) {
}
