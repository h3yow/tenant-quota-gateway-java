package com.github.h3yow.tenantquotagatewayjava.quota;

/**
 * 单租户令牌桶：惰性补充，判定与扣减在同一把锁内原子完成，杜绝超发。
 * 时间源可注入，便于测试临界突发。
 */
public final class TokenBucket {

    private final TimeSource timeSource;
    private double ratePerSecond;
    private long capacity;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double ratePerSecond, long capacity) {
        this(ratePerSecond, capacity, System::nanoTime);
    }

    TokenBucket(double ratePerSecond, long capacity, TimeSource timeSource) {
        this.timeSource = timeSource;
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillNanos = timeSource.nanoTime();
    }

    /**
     * 尝试获取 1 个令牌。
     *
     * @return true 表示允许（令牌已扣减）；false 表示速率超限（不扣减）
     */
    public synchronized boolean tryConsume() {
        refill();
        if (tokens >= 1.0d) {
            tokens -= 1.0d;
            return true;
        }
        return false;
    }

    /**
     * 使用新策略更新桶：先按旧速率补齐，再限制到新容量以内，保证刷新不丢可用令牌、也不凭空增发。
     */
    public synchronized void update(double ratePerSecond, long capacity) {
        refill();
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.tokens = Math.min(tokens, capacity);
    }

    public synchronized long availableTokens() {
        refill();
        return (long) tokens;
    }

    private void refill() {
        long now = timeSource.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos > 0) {
            double produced = (elapsedNanos / 1_000_000_000.0d) * ratePerSecond;
            tokens = Math.min(capacity, tokens + produced);
            lastRefillNanos = now;
        }
    }

    @FunctionalInterface
    interface TimeSource {
        long nanoTime();
    }
}
