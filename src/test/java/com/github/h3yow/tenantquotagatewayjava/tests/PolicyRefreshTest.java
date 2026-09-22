package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.AuditEvent;
import com.github.h3yow.tenantquotagatewayjava.limit.Decision;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.PolicySnapshot;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略刷新一致性测试。
 */
class PolicyRefreshTest {

    private void print(Decision d) {
        System.out.printf("[刷新] requestId=%s tenant=%s reason=%s policyV=%s quotaRemain=%d%n        依据: %s%n",
                d.requestId(), d.tenantId(), d.reason(), d.policyVersion(),
                d.quotaRemaining(), d.detail());
    }

    @Test
    void refreshKeepsUsageWhenQuotaUnchangedAndRejectsWithStableReasons() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        TenantPolicy v1 = new TenantPolicy("tenant-a", 5, 1.0, 5, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, v1));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 500);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false, 60_000);

        // v1 下用掉 3 个配额
        for (int i = 0; i < 3; i++) {
            Decision d = engine.evaluate("v1-req-" + i, "tenant-a", null);
            print(d);
            assertEquals(DecisionReason.ALLOWED, d.reason());
            assertEquals(1L, d.policyVersion());
        }

        // 刷新到 v2：仅调整速率参数，配额上限与窗口不变 -> 已用 3 必须保留，不丢失
        TenantPolicy v2 = new TenantPolicy("tenant-a", 10, 2.0, 5, 60);
        policies.swap(TestPolicies.snapshot(2, v2));
        PolicySnapshot refreshed = engine.reloadPolicies();
        assertEquals(2L, refreshed.version());

        Decision underV2 = engine.evaluate("v2-req-0", "tenant-a", null);
        print(underV2);
        assertEquals(DecisionReason.ALLOWED, underV2.reason());
        assertEquals(2L, underV2.policyVersion());
        assertEquals(1L, underV2.quotaRemaining(), "刷新后已用量必须保留(已用4/5)");

        Decision boundary = engine.evaluate("v2-req-1", "tenant-a", null);
        print(boundary);
        assertEquals(DecisionReason.ALLOWED, boundary.reason());
        assertEquals(0L, boundary.quotaRemaining());

        Decision over = engine.evaluate("v2-req-2", "tenant-a", null);
        print(over);
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, over.reason());
        assertEquals(2L, over.policyVersion(), "拒绝判定必须引用新版本，不出现错乱");

        // 刷新到 v3：配额维度变化（上限提高）-> 开新窗口，用量按新策略重新计
        TenantPolicy v3 = new TenantPolicy("tenant-a", 10, 2.0, 50, 60);
        policies.swap(TestPolicies.snapshot(3, v3));
        engine.reloadPolicies();
        Decision afterShapeChange = engine.evaluate("v3-req-0", "tenant-a", null);
        print(afterShapeChange);
        assertEquals(DecisionReason.ALLOWED, afterShapeChange.reason());
        assertEquals(49L, afterShapeChange.quotaRemaining(), "配额维度变更应开新窗口");

        // 每次刷新都留下带依据的 POLICY_CHANGE 审计
        List<AuditEvent> changes = audit.recentEvents().stream()
                .filter(e -> e.kind() == AuditEvent.Kind.POLICY_CHANGE).toList();
        assertEquals(2, changes.size());
        changes.forEach(e -> System.out.printf("[审计-策略变更] seq=%d v=%s 依据=%s%n",
                e.sequence(), e.policyVersion(), e.basis()));
    }

    @Test
    void concurrentRequestsDuringRepeatedRefreshNeverCorruptCounters()
            throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        // 所有版本速率容量都给足，使允许数仅由配额决定，便于精确核对
        TenantPolicy v1 = new TenantPolicy("tenant-a", 100_000, 100_000.0, 40, 60);
        TenantPolicy v2 = new TenantPolicy("tenant-a", 100_000, 100_000.0, 40, 60);
        TenantPolicy v3 = new TenantPolicy("tenant-a", 100_000, 100_000.0, 40, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, v1));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 10_000);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false, 60_000);

        int requestThreads = 16;
        int requestsPerThread = 5; // 共 80 个请求，配额 40
        int refresherThreads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Decision> decisions = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                requestThreads + refresherThreads);
        AtomicInteger version = new AtomicInteger(1);

        for (int t = 0; t < requestThreads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < requestsPerThread; i++) {
                    Decision d = engine.evaluate(
                            "r-" + tid + "-" + i, "tenant-a", null);
                    decisions.add(d);
                }
            });
        }
        for (int t = 0; t < refresherThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 10; i++) {
                    int nv = version.incrementAndGet();
                    policies.swap(TestPolicies.snapshot(nv,
                            new TenantPolicy("tenant-a", 100_000, 100_000.0, 40, 60)));
                    engine.reloadPolicies();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        long allowed = decisions.stream().filter(Decision::allowed).count();
        long quotaDenied = decisions.stream()
                .filter(d -> d.reason() == DecisionReason.QUOTA_EXHAUSTED).count();
        long otherDenied = decisions.stream()
                .filter(d -> !d.allowed() && d.reason() != DecisionReason.QUOTA_EXHAUSTED)
                .count();
        System.out.printf("[刷新并发] 总请求=%d 允许=%d 配额拒绝=%d 其他拒绝=%d%n",
                decisions.size(), allowed, quotaDenied, otherDenied);
        decisions.stream().filter(d -> !d.allowed()).limit(3).forEach(this::print);

        assertEquals(80, decisions.size());
        assertEquals(40L, allowed, "刷新交错期间允许数必须严格等于配额，不超发不少计");
        assertEquals(40L, quotaDenied, "其余请求必须是可区分的配额拒绝");
        assertEquals(0L, otherDenied, "刷新不得产生降级/原因错乱");
        // 每个判定引用的策略版本都必须是真实发布过的版本
        decisions.forEach(d -> assertTrue(d.policyVersion() >= 1L && d.policyVersion() <= 41L,
                "判定引用了不存在的策略版本: " + d.policyVersion()));
    }
}
