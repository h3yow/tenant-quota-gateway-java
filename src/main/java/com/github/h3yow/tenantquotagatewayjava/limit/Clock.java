package com.github.h3yow.tenantquotagatewayjava.limit;

import java.time.Instant;

/** 时钟抽象，便于在测试中精确控制时间推进。 */
public interface Clock {

    Instant now();

    static Clock system() {
        return Instant::now;
    }
}
