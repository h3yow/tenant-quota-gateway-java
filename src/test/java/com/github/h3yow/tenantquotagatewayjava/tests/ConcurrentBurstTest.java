package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.Decision;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发突发测试：大量线程同时冲击同一租户，允许数必须严格等于令牌容量与配额上限，
 * 不得超发；每个线程拿到的拒绝原因可区分。
 */
class ConcurrentBurstTest {

    @Test
    void concurrentBurstNeverOverIssues() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        int threads = 200;
        // 容量 20、配额 20（两者一致，便于断言允许总数严格等于 20）
        TenantPolicy a = new TenantPolicy("tenant-burst", 20, 0.0, 20, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a));
        QuotaLimitEngine engine = new QuotaLimitEngine(policies,
                new DefaultAuditLogger(clock, 2000), clock, false, 60_000);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Decision> decisions = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Decision d = engine.evaluate("burst-" + idx, "tenant-burst", null);
                decisions.add(d);
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        long allowed = decisions.stream().filter(Decision::allowed).count();
        long rateDenied = decisions.stream()
                .filter(d -> d.reason() == DecisionReason.RATE_LIMITED).count();
        long quotaDenied = decisions.stream()
                .filter(d -> d.reason() == DecisionReason.QUOTA_EXHAUSTED).count();

        System.out.printf("[并发突发] 总请求=%d 允许=%d 速率拒绝=%d 配额拒绝=%d (期望允许严格=20)%n",
                threads, allowed, rateDenied, quotaDenied);
        decisions.stream().filter(d -> !d.allowed()).limit(5).forEach(d ->
                System.out.printf("  [拒绝样例] requestId=%s reason=%s 依据=%s%n",
                        d.requestId(), d.reason(), d.detail()));

        assertEquals(20L, allowed, "并发下允许数必须严格等于容量，不得超发");
        assertEquals(threads - 20L, rateDenied + quotaDenied, "其余请求必须被明确拒绝");
        // 计量状态一致性：已用配额等于允许数
        var view = engine.describeTenant("tenant-burst").orElseThrow();
        assertEquals(20L, ((Number) view.get("quotaUsed")).longValue(), "后台已用量必须与允许数一致");
    }
}
