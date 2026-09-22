package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.Clock;
import com.github.h3yow.tenantquotagatewayjava.limit.Decision;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户隔离、拒绝原因区分与临界额度测试。
 */
class TenantIsolationTest {

    private final AtomicInteger seq = new AtomicInteger();

    private String req(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private void print(Decision d) {
        System.out.printf("[判定] requestId=%s tenant=%s reason=%s rateRemain=%d quotaRemain=%d%n        依据: %s%n",
                d.requestId(), d.tenantId(), d.reason(), d.rateRemaining(),
                d.quotaRemaining(), d.detail());
    }

    private QuotaLimitEngine engine(TestPolicies policies, MutableClock clock) {
        return new QuotaLimitEngine(policies, new DefaultAuditLogger(clock, 100), clock,
                false, 60_000);
    }

    @Test
    void missingAndUnknownTenantAreRejectedWithDistinctReasons() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        TenantPolicy a = new TenantPolicy("tenant-a", 10, 1.0, 100, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a));
        QuotaLimitEngine engine = engine(policies, clock);

        Decision missing = engine.evaluate(req("req"), "  ", null);
        print(missing);
        assertEquals(DecisionReason.MISSING_TENANT, missing.reason());
        assertEquals(400, missing.reason().getHttpStatus());

        Decision unknown = engine.evaluate(req("req"), "tenant-x", null);
        print(unknown);
        assertEquals(DecisionReason.UNKNOWN_TENANT, unknown.reason());
        assertEquals(403, unknown.reason().getHttpStatus());

        Decision ok = engine.evaluate(req("req"), "tenant-a", null);
        print(ok);
        assertEquals(DecisionReason.ALLOWED, ok.reason());
    }

    @Test
    void oneTenantBurstDoesNotAffectAnotherTenant() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        // A: 突发容量 3，配额 100；B: 突发容量 2，配额 100
        TenantPolicy a = new TenantPolicy("tenant-a", 3, 1.0, 100, 60);
        TenantPolicy b = new TenantPolicy("tenant-b", 2, 1.0, 100, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a, b));
        QuotaLimitEngine engine = engine(policies, clock);

        // A 连续突发 4 个：前 3 允许，第 4 个速率拒绝
        int aAllowed = 0;
        Decision lastA = null;
        for (int i = 0; i < 4; i++) {
            lastA = engine.evaluate(req("A"), "tenant-a", null);
            print(lastA);
            if (lastA.allowed()) {
                aAllowed++;
            }
        }
        assertEquals(3, aAllowed);
        assertEquals(DecisionReason.RATE_LIMITED, lastA.reason());
        assertTrue(lastA.retryAfterMillis() > 0, "速率拒绝应给出重试等待");

        // B 不受 A 的突发影响：仍有完整的 2 个突发额度
        Decision b1 = engine.evaluate(req("B"), "tenant-b", null);
        Decision b2 = engine.evaluate(req("B"), "tenant-b", null);
        print(b1);
        print(b2);
        assertEquals(DecisionReason.ALLOWED, b1.reason());
        assertEquals(DecisionReason.ALLOWED, b2.reason());
        assertEquals(0L, b2.rateRemaining());

        // 推进 1 秒，A 恰好补充 1 个令牌
        clock.advanceSeconds(1);
        Decision aRecovered = engine.evaluate(req("A"), "tenant-a", null);
        print(aRecovered);
        assertEquals(DecisionReason.ALLOWED, aRecovered.reason());
    }

    @Test
    void quotaBoundaryExactlyLimitAllowedThenRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        // 速率容量足够大，确保由配额触发拒绝；配额 3/窗口
        TenantPolicy a = new TenantPolicy("tenant-c", 100, 100.0, 3, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a));
        QuotaLimitEngine engine = engine(policies, clock);

        for (int i = 0; i < 3; i++) {
            Decision d = engine.evaluate(req("C"), "tenant-c", null);
            print(d);
            assertEquals(DecisionReason.ALLOWED, d.reason(), "第 " + (i + 1) + " 个请求应在额度内");
            assertEquals(2 - i, d.quotaRemaining(), "剩余配额应随扣减递减");
        }

        // 第 4 个：临界外，必须配额拒绝，原因与速率拒绝可区分
        Decision over = engine.evaluate(req("C"), "tenant-c", null);
        print(over);
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, over.reason());
        assertEquals(0L, over.quotaRemaining());
        assertTrue(over.retryAfterMillis() > 0, "配额拒绝应给出窗口重置等待");

        // 推进整个窗口后额度恢复，不多计也不少计
        clock.advanceSeconds(60);
        Decision nextWindow = engine.evaluate(req("C"), "tenant-c", null);
        print(nextWindow);
        assertEquals(DecisionReason.ALLOWED, nextWindow.reason());
        assertEquals(2L, nextWindow.quotaRemaining());
    }
}
