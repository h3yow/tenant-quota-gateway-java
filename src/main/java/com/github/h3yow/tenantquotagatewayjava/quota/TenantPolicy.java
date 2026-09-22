package com.github.h3yow.tenantquotagatewayjava.quota;

/**
 * 单个租户的限流与配额策略。
 *
 * @param tenantId           租户标识
 * @param rateLimitPerSecond 每秒补充的令牌数（速率上限）
 * @param burstCapacity      令牌桶容量（突发上限）
 * @param quotaLimit         配额窗口内允许的最大计费请求数
 * @param quotaWindowMillis  配额窗口长度（毫秒）
 */
public record TenantPolicy(String tenantId,
                           double rateLimitPerSecond,
                           long burstCapacity,
                           long quotaLimit,
                           long quotaWindowMillis) {
}
