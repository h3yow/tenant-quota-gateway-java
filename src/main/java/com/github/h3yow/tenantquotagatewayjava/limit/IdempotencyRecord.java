package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 幂等键对应的首次判定记录。重试携带相同键时直接返回该结果，不重复扣减。
 *
 * @param key            幂等键
 * @param firstRequestId 首次请求标识
 * @param decision       首次判定结果
 * @param expiresAtEpochMilli 过期时间（惰性清理）
 */
public record IdempotencyRecord(
        String key,
        String firstRequestId,
        Decision decision,
        long expiresAtEpochMilli
) {

    public boolean expiredAt(long nowEpochMilli) {
        return nowEpochMilli >= expiresAtEpochMilli;
    }
}
