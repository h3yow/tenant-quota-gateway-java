package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.Decision;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复提交/重试幂等性测试。
 */
class IdempotencyTest {

    private final AtomicInteger seq = new AtomicInteger();

    private QuotaLimitEngine engine(TestPolicies policies, MutableClock clock, long ttl) {
        return new QuotaLimitEngine(policies, new DefaultAuditLogger(clock, 500), clock,
                false, ttl);
    }

    private void print(Decision d) {
        System.out.printf("[幂等] requestId=%s tenant=%s key=%s reason=%s quotaRemain=%d%n        依据: %s%n",
                d.requestId(), d.tenantId(), d.idempotencyKey(), d.reason(),
                d.quotaRemaining(), d.detail());
    }

    @Test
    void duplicateSubmissionWithSameKeyDeductedOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        TenantPolicy a = new TenantPolicy("tenant-a", 100, 100.0, 10, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a));
        QuotaLimitEngine engine = engine(policies, clock, 60_000);

        Decision first = engine.evaluate("order-1", "tenant-a", "key-abc");
        Decision retry1 = engine.evaluate("order-1-retry", "tenant-a", "key-abc");
        Decision retry2 = engine.evaluate("order-1-retry2", "tenant-a", "key-abc");
        print(first);
        print(retry1);
        print(retry2);

        assertEquals(DecisionReason.ALLOWED, first.reason());
        assertEquals(DecisionReason.IDEMPOTENT_REPLAY, retry1.reason());
        assertEquals(DecisionReason.IDEMPOTENT_REPLAY, retry2.reason());
        // 三次提交只扣一次配额：剩余 9
        assertEquals(9L, first.quotaRemaining());
        assertEquals(9L, retry1.quotaRemaining());
        assertEquals(9L, retry2.quotaRemaining());
        assertEquals(1L, ((Number) engine.describeTenant("tenant-a").orElseThrow()
                .get("quotaUsed")).longValue(), "重复提交不得重复扣减用量");

        // 不同幂等键是独立业务请求，正常扣减
        Decision other = engine.evaluate("order-2", "tenant-a", "key-xyz");
        print(other);
        assertEquals(DecisionReason.ALLOWED, other.reason());
        assertEquals(8L, other.quotaRemaining());
    }

    @Test
    void concurrentSameKeyOnlyOneDeduction() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        TenantPolicy a = new TenantPolicy("tenant-a", 100, 100.0, 10, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a));
        QuotaLimitEngine engine = engine(policies, clock, 60_000);

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Decision> results = new ConcurrentLinkedQueue<>();

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
                results.add(engine.evaluate("concurrent-" + idx, "tenant-a", "same-key"));
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long firstAllowed = results.stream()
                .filter(d -> d.reason() == DecisionReason.ALLOWED).count();
        long replays = results.stream()
                .filter(d -> d.reason() == DecisionReason.IDEMPOTENT_REPLAY).count();
        System.out.printf("[并发同键] 总请求=%d 首次允许=%d 幂等重放=%d%n",
                threads, firstAllowed, replays);
        results.stream().limit(3).forEach(this::print);

        assertEquals(1L, firstAllowed, "同一幂等键并发只允许一次扣减");
        assertEquals(threads - 1L, replays, "其余并发请求必须命中幂等重放");
        assertEquals(1L, ((Number) engine.describeTenant("tenant-a").orElseThrow()
                .get("quotaUsed")).longValue());
    }

    @Test
    void rejectedFirstRequestNotCached_retrySucceedsAfterRecoveryAndExpiredKeyRebills() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        // 速率充足、配额仅 1；TTL 120s（2 个窗口），便于区分窗口恢复与键过期两个阶段
        TenantPolicy a = new TenantPolicy("tenant-a", 100, 100.0, 1, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a));
        QuotaLimitEngine engine = engine(policies, clock, 120_000);

        // 先用无键请求耗尽本窗口唯一配额
        Decision warmup = engine.evaluate("warmup", "tenant-a", null);
        assertEquals(DecisionReason.ALLOWED, warmup.reason());

        // 带幂等键的首次提交因配额不足被拒绝——拒绝结论不缓存
        Decision first = engine.evaluate("pay-1", "tenant-a", "pay-key");
        Decision retryWhileExhausted = engine.evaluate("pay-1b", "tenant-a", "pay-key");
        print(first);
        print(retryWhileExhausted);
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, first.reason());
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, retryWhileExhausted.reason());
        // 被拒未缓存：窗口内已用量仍只来自 warmup（1），拒绝不扣减
        assertEquals(1L, ((Number) engine.describeTenant("tenant-a").orElseThrow()
                .get("quotaUsed")).longValue());

        // 窗口滚动、额度恢复后，同键重试成功并计费，audit 与返回明确显示这是新的成功
        clock.advanceSeconds(60);
        Decision recovered = engine.evaluate("pay-1c", "tenant-a", "pay-key");
        print(recovered);
        assertEquals(DecisionReason.ALLOWED, recovered.reason());

        // 此后同键重试命中成功记录，不再重复计费
        Decision replay = engine.evaluate("pay-1d", "tenant-a", "pay-key");
        print(replay);
        assertEquals(DecisionReason.IDEMPOTENT_REPLAY, replay.reason());
        assertEquals(1L, ((Number) engine.describeTenant("tenant-a").orElseThrow()
                .get("quotaUsed")).longValue());

        // TTL 过期后（成功记录写于 t=60s，120s TTL，故需越过 t=180s），同一键视为新业务请求
        clock.advanceMillis(120_001);
        Decision afterTtl = engine.evaluate("pay-new", "tenant-a", "pay-key");
        print(afterTtl);
        assertEquals(DecisionReason.ALLOWED, afterTtl.reason());
    }
}
