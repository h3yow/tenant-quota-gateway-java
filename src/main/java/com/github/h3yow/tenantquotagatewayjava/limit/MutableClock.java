package com.github.h3yow.tenantquotagatewayjava.limit;

import java.time.Instant;

/** 可由测试手动推进的时钟。 */
public final class MutableClock implements Clock {

    private volatile Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    @Override
    public Instant now() {
        return instant;
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    public void advanceMillis(long millis) {
        this.instant = instant.plusMillis(millis);
    }

    public void advanceSeconds(long seconds) {
        this.instant = instant.plusSeconds(seconds);
    }
}
