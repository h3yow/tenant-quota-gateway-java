package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.AuditEvent;
import com.github.h3yow.tenantquotagatewayjava.limit.Decision;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.Reservation;
import com.github.h3yow.tenantquotagatewayjava.limit.Settlement;
import com.github.h3yow.tenantquotagatewayjava.limit.SettlementStatus;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预占 / 结算（先占用、后结算）模型的自动化测试：
 * 并发占用不超额、结算多退少补、重复/乱序结算只生效一次、占用超时释放，
 * 以及窗口滚动/策略刷新后结算落到正确窗口、普通路径不回归。
 * 每条断言前均打印 requestId / reservationId 与判定依据。
 */
class ReservationSettlementTest {

    private static final long TTL_MS = 10_000L;
    private static final long RECORD_TTL_MS = 600_000L;

    private QuotaLimitEngine engine(TestPolicies policies, MutableClock clock,
                                    DefaultAuditLogger audit, boolean failOpen) {
        return new QuotaLimitEngine(policies, audit, clock, failOpen, 60_000L,
                TTL_MS, RECORD_TTL_MS);
    }

    private TenantPolicy policy(long capacity, long limit, long windowSeconds) {
        return new TenantPolicy("tenant-a", capacity, (double) capacity, limit, windowSeconds);
    }

    private void printReserve(Reservation r) {
        System.out.printf("[预占] requestId=%s reservationId=%s reason=%s estimate=%d 剩余可放行=%d 在途=%d 失效@%d%n        依据: %s%n",
                r.requestId(), r.reservationId(), r.reason(), r.estimatedAmount(),
                r.quotaRemaining(), r.heldTotal(), r.expiresAtEpochMilli(), r.detail());
    }

    private void printSettle(Settlement s) {
        System.out.printf("[结算] requestId=%s reservationId=%s status=%s effective=%s 预占=%d 实际=%d 调整=%d 计入=%d%n        依据: %s%n",
                s.requestId(), s.reservationId(), s.status(), s.effective(),
                s.reservedAmount(), s.actualAmount(), s.delta(), s.settledUsed(), s.detail());
    }

    private void print(Decision d) {
        System.out.printf("[判定] requestId=%s tenant=%s reason=%s quotaRemain=%d%n        依据: %s%n",
                d.requestId(), d.tenantId(), d.reason(), d.quotaRemaining(), d.detail());
    }

    private long used(QuotaLimitEngine engine) {
        return ((Number) engine.describeTenant("tenant-a").orElseThrow().get("quotaUsed"))
                .longValue();
    }

    private long held(QuotaLimitEngine engine) {
        return ((Number) engine.describeTenant("tenant-a").orElseThrow().get("quotaHeld"))
                .longValue();
    }

    @Test
    void concurrentReservationsNeverExceedLimitAndIncludeHeld() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        // 速率给足，配额上限 20，每个预占申请 3
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(1000, 20, 60)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 5000);
        QuotaLimitEngine engine = engine(policies, clock, audit, false);

        int threads = 20; // 20*3=60 > 上限 20
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Reservation> results = new ConcurrentLinkedQueue<>();
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
                results.add(engine.reserve("res-req-" + idx, "tenant-a",
                        "reservation-" + idx, 3L));
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long accepted = results.stream().filter(Reservation::allowed).count();
        long denied = results.stream()
                .filter(r -> r.reason() == DecisionReason.QUOTA_EXHAUSTED).count();
        long heldSum = held(engine);
        System.out.printf("[并发预占] 总请求=%d 接受=%d 配额拒绝=%d 在途占用合计=%d (期望 接受*3 严格<=20)%n",
                threads, accepted, denied, heldSum);
        results.stream().filter(r -> !r.allowed()).limit(3).forEach(this::printReserve);

        assertEquals(6L, accepted, "20 个各申请 3 的并发预占，最多接受 floor(20/3)=6 个，不得超发");
        assertEquals(18L, heldSum, "在途占用合计必须等于接受量*3");
        assertEquals(threads - 6L, denied, "其余请求必须得到可区分的 QUOTA_EXHAUSTED");
        long minRemaining = results.stream().filter(Reservation::allowed)
                .mapToLong(Reservation::quotaRemaining).min().orElseThrow();
        assertEquals(2L, minRemaining, "占满 18 后窗口可再放行额度=20-18=2（先到的请求看到更多）");
        // 再补一笔预占把剩余 2 也占满，随后普通请求必须拿不到额度
        Reservation fill = engine.reserve("res-fill", "tenant-a", "reservation-fill", 2L);
        printReserve(fill);
        assertEquals(DecisionReason.ALLOWED, fill.reason());
        Decision normal = engine.evaluate("normal-during-held", "tenant-a", null);
        System.out.printf("[并发预占] 占满后普通请求 requestId=%s reason=%s%n        依据: %s%n",
                normal.requestId(), normal.reason(), normal.detail());
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, normal.reason(),
                "被占住的额度不能被普通请求复用");
    }

    @Test
    void settlementRefundsOverReserveAndSupplementsUnderReserve() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(100, 20, 60)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 1000);
        QuotaLimitEngine engine = engine(policies, clock, audit, false);

        // 业务 A：预占 8，实际只用 3 -> 退回 5
        Reservation ra = engine.reserve("req-a", "tenant-a", "job-a", 8L);
        printReserve(ra);
        assertEquals(DecisionReason.ALLOWED, ra.reason());
        assertEquals(8L, held(engine));
        Settlement sa = engine.settleReservation("settle-a", "tenant-a", "job-a", 3L);
        printSettle(sa);
        assertEquals(SettlementStatus.SETTLED_REFUNDED, sa.status());
        assertEquals(-5L, sa.delta());
        assertEquals(3L, used(engine));
        assertEquals(0L, held(engine), "结算后占用必须释放");
        assertEquals(17L, sa.quotaRemaining());

        // 业务 B：预占 2，实际用 6 -> 补扣 4
        Reservation rb = engine.reserve("req-b", "tenant-a", "job-b", 2L);
        printReserve(rb);
        Settlement sb = engine.settleReservation("settle-b", "tenant-a", "job-b", 6L);
        printSettle(sb);
        assertEquals(SettlementStatus.SETTLED_SUPPLEMENTED, sb.status());
        assertEquals(4L, sb.delta());
        assertEquals(9L, used(engine), "累计真实用量 3+6=9");
        assertEquals(0L, held(engine));

        // 业务 C：预占 2，实际恰好用 2 -> 无退补
        Reservation rc = engine.reserve("req-c", "tenant-a", "job-c", 2L);
        Settlement sc = engine.settleReservation("settle-c", "tenant-a", "job-c", 2L);
        printSettle(sc);
        assertEquals(SettlementStatus.SETTLED_EXACT, sc.status());
        assertEquals(0L, sc.delta());
        assertEquals(11L, used(engine));

        // 业务 D：预占 6（已用11+占用6=17 放行），实际 12 -> 真实用量 23 超过上限 20，
        // 结算仍生效一次但标记超用，不静默截断
        Reservation rd = engine.reserve("req-d", "tenant-a", "job-d", 6L);
        printReserve(rd);
        Settlement sd = engine.settleReservation("settle-d", "tenant-a", "job-d", 12L);
        printSettle(sd);
        assertEquals(SettlementStatus.SETTLED_OVER_LIMIT, sd.status());
        assertEquals(6L, sd.delta());
        assertEquals(23L, used(engine), "按真实用量记账");
    }

    @Test
    void duplicateAndOutOfOrderSettlementTakeEffectOnce() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(100, 50, 60)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 2000);
        QuotaLimitEngine engine = engine(policies, clock, audit, false);

        engine.reserve("req-1", "tenant-a", "job-x", 10L);
        Settlement first = engine.settleReservation("s-1", "tenant-a", "job-x", 4L);
        printSettle(first);
        assertEquals(SettlementStatus.SETTLED_REFUNDED, first.status());
        assertTrue(first.effective());
        assertEquals(4L, used(engine));

        // 重复结算：即使上报不同的实际用量，也只返回首次结论，不再次计费
        Settlement dup1 = engine.settleReservation("s-2", "tenant-a", "job-x", 99L);
        Settlement dup2 = engine.settleReservation("s-3", "tenant-a", "job-x", 4L);
        printSettle(dup1);
        printSettle(dup2);
        assertEquals(SettlementStatus.SETTLEMENT_DUPLICATE, dup1.status());
        assertEquals(SettlementStatus.SETTLEMENT_DUPLICATE, dup2.status());
        assertFalse(dup1.effective());
        assertEquals(4L, used(engine), "重复结算不得改变计量");
        assertEquals(0L, held(engine));
        assertTrue(dup1.detail().contains("s-1") || dup1.detail().contains("job-x"));

        // 乱序到达：先结算过的预占不能被再次占用；同 reservationId 重提预占被拒
        Reservation reReserve = engine.reserve("req-1b", "tenant-a", "job-x", 5L);
        printReserve(reReserve);
        assertEquals(DecisionReason.INVALID_REQUEST, reReserve.reason());
        assertEquals(4L, used(engine));

        // 并发重复结算：多线程同时提交同一预占的结算，只有一个真正生效
        engine.reserve("req-2", "tenant-a", "job-y", 8L);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Settlement> results = new ConcurrentLinkedQueue<>();
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
                // 乱序：一半线程报 2，一半报 7，但首个生效值锁定结论
                results.add(engine.settleReservation("cs-" + idx, "tenant-a", "job-y",
                        idx % 2 == 0 ? 2L : 7L));
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        long effective = results.stream().filter(Settlement::effective).count();
        long duplicates = results.stream()
                .filter(s -> s.status() == SettlementStatus.SETTLEMENT_DUPLICATE).count();
        System.out.printf("[并发乱序结算] 总提交=%d 真正生效=%d 幂等拦截=%d%n",
                threads, effective, duplicates);
        assertEquals(1L, effective, "同一笔业务的并发结算只能生效一次");
        assertEquals(threads - 1L, duplicates);
        // 生效的那一笔：实际用量必然是 2 或 7 中的一个，且总已用只包含一次
        long jobYUsed = used(engine) - 4L;
        assertTrue(jobYUsed == 2L || jobYUsed == 7L, "只可能按首个生效值记账，实际=" + jobYUsed);
        assertEquals(0L, held(engine));
    }

    @Test
    void reservationExpiresAndIsReleasedThenLateSettleRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(100, 6, 60)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 2000);
        QuotaLimitEngine engine = engine(policies, clock, audit, false);

        Reservation r = engine.reserve("req-exp", "tenant-a", "job-late", 6L);
        printReserve(r);
        assertEquals(6L, held(engine));
        assertEquals(0L, r.quotaRemaining());

        // 未超时：额度仍被占住，普通请求拿不到
        clock.advanceMillis(TTL_MS - 1);
        Decision blocked = engine.evaluate("normal-blocked", "tenant-a", null);
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, blocked.reason());

        // 到达超时时刻：主动扫描释放
        clock.advanceMillis(1);
        int released = engine.sweepExpiredReservations();
        System.out.printf("[超时释放] 扫描释放预占数=%d%n", released);
        assertEquals(1, released);
        assertEquals(0L, held(engine), "超时占用必须自动释放");
        assertEquals(0L, used(engine), "超时释放不是计费");

        // 释放后旧预占不能再被当成有效额度：迟到结算被拒，不发生计费
        Settlement late = engine.settleReservation("late-settle", "tenant-a", "job-late", 6L);
        printSettle(late);
        assertEquals(SettlementStatus.SETTLEMENT_EXPIRED, late.status());
        assertFalse(late.effective());
        assertEquals(0L, used(engine));

        // 释放出的额度可被新业务使用；新业务占 2 结 2，给随后的惰性场景留出 4
        Reservation newJob = engine.reserve("req-new", "tenant-a", "job-new", 2L);
        printReserve(newJob);
        assertEquals(DecisionReason.ALLOWED, newJob.reason());
        Settlement newSettled = engine.settleReservation("new-settle", "tenant-a", "job-new", 2L);
        assertEquals(SettlementStatus.SETTLED_EXACT, newSettled.status());

        // 超时释放有独立审计，且与结算事件可区分
        List<AuditEvent> expirations = audit.recentEvents().stream()
                .filter(e -> e.kind() == AuditEvent.Kind.RESERVATION_EXPIRED).toList();
        assertEquals(1, expirations.size());
        expirations.forEach(e -> System.out.printf("[审计-超时释放] tenant=%s 依据=%s%n",
                e.tenantId(), e.basis()));

        // 惰性释放路径：再建一笔，不主动扫描，直接等过期后走普通请求触发释放
        Reservation lazy = engine.reserve("req-lazy", "tenant-a", "job-lazy", 4L);
        assertEquals(4L, held(engine));
        clock.advanceMillis(TTL_MS + 1);
        Decision afterLazyExpiry = engine.evaluate("normal-triggers-sweep", "tenant-a", null);
        System.out.printf("[超时释放-惰性] requestId=%s reason=%s held=%d%n",
                afterLazyExpiry.requestId(), afterLazyExpiry.reason(), held(engine));
        assertEquals(DecisionReason.ALLOWED, afterLazyExpiry.reason(),
                "普通判定路径必须惰性释放超时占用");
        assertEquals(0L, held(engine));
    }

    @Test
    void windowRollAndPolicyRefreshKeepInFlightAndSettleOnCorrectWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TenantPolicy v1 = policy(100, 10, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, v1));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 3000);
        // 本测试需要"窗口已滚动但预占尚未超时"，故预占 TTL(120s) 大于窗口(60s)
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false, 60_000L,
                120_000L, RECORD_TTL_MS);

        // 在 t=0 预占全部 10 个配额（占满，普通请求随后应被配额闸门拒绝）
        Reservation held = engine.reserve("req-held", "tenant-a", "job-held", 10L);
        printReserve(held);
        assertEquals(10L, held(engine));

        // 同维度刷新（仅速率变化）：在途占用保留，判定不错乱、用量不消失
        TenantPolicy v2 = new TenantPolicy("tenant-a", 200, 200.0, 10, 60);
        policies.swap(TestPolicies.snapshot(2, v2));
        engine.reloadPolicies();
        assertEquals(10L, held(engine), "同维度刷新不得清空在途占用");
        Decision blockedAfterRefresh = engine.evaluate("after-refresh", "tenant-a", null);
        System.out.printf("[刷新] requestId=%s reason=%s%n        依据: %s%n",
                blockedAfterRefresh.requestId(), blockedAfterRefresh.reason(),
                blockedAfterRefresh.detail());
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, blockedAfterRefresh.reason());
        assertEquals(2L, blockedAfterRefresh.policyVersion());

        // 窗口滚动后结算：落到旧窗口记账，不影响当前窗口
        clock.advanceSeconds(60);
        Settlement oldWindowSettle =
                engine.settleReservation("settle-old", "tenant-a", "job-held", 6L);
        printSettle(oldWindowSettle);
        assertEquals(SettlementStatus.SETTLED_CLOSED_WINDOW, oldWindowSettle.status());
        assertTrue(oldWindowSettle.effective());
        assertEquals(1L, oldWindowSettle.policyVersion(), "必须落到预占时的窗口/版本");
        assertEquals(0L, used(engine), "关闭窗口的结算不计入当前窗口用量");
        assertEquals(0L, held(engine));

        // 新窗口额度完整可用，且上限用当前策略
        Reservation fresh = engine.reserve("req-fresh", "tenant-a", "job-fresh", 9L);
        printReserve(fresh);
        assertEquals(DecisionReason.ALLOWED, fresh.reason());
        assertEquals(9L, held(engine));

        // 配额维度变更（窗口长度变化）：按设计开新窗口。job-fresh 创建于变更后的当前时刻，
        // 属于新窗口；旧窗口的 job-held 已结算，重复提交只返回钉在 v1 的幂等结论。
        TenantPolicy v3 = new TenantPolicy("tenant-a", 200, 200.0, 10, 120);
        policies.swap(TestPolicies.snapshot(3, v3));
        engine.reloadPolicies();

        Settlement heldDup = engine.settleReservation("settle-held-dup", "tenant-a",
                "job-held", 6L);
        printSettle(heldDup);
        assertEquals(SettlementStatus.SETTLEMENT_DUPLICATE, heldDup.status());
        assertEquals(1L, heldDup.policyVersion(), "重复结论仍引用预占时的窗口/版本");

        Settlement shapeSettle =
                engine.settleReservation("settle-shape", "tenant-a", "job-fresh", 9L);
        printSettle(shapeSettle);
        assertEquals(SettlementStatus.SETTLED_EXACT, shapeSettle.status(),
                "job-fresh 属于形状变更后的新窗口，按新窗口正常结算");
        assertEquals(3L, shapeSettle.policyVersion());
        assertEquals(9L, used(engine), "新窗口用量只来自新窗口的结算");
        assertEquals(0L, held(engine), "旧窗口在途不结转，新窗口结算后占用归零");

        // 新窗口按 v3 上限 10 计量：再预占 1 成功，再要 1 即超上限被拒
        Reservation lastOne = engine.reserve("req-last", "tenant-a", "job-last", 1L);
        printReserve(lastOne);
        assertEquals(DecisionReason.ALLOWED, lastOne.reason());
        Reservation overflow = engine.reserve("req-over", "tenant-a", "job-over", 1L);
        printReserve(overflow);
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, overflow.reason(),
                "维度变更后必须按新上限计量，不错乱");
    }

    @Test
    void invalidAndUnknownAndDegradationPathsAreDistinguishable() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(100, 10, 60)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 2000);
        QuotaLimitEngine engine = engine(policies, clock, audit, false);

        Reservation noId = engine.reserve("r-noid", "tenant-a", null, 3L);
        Reservation badAmount = engine.reserve("r-bad", "tenant-a", "job-bad", 0L);
        Reservation unknown = engine.reserve("r-unknown", "ghost", "job-x", 3L);
        printReserve(noId);
        printReserve(badAmount);
        printReserve(unknown);
        assertEquals(DecisionReason.INVALID_REQUEST, noId.reason());
        assertEquals(DecisionReason.INVALID_REQUEST, badAmount.reason());
        assertEquals(DecisionReason.UNKNOWN_TENANT, unknown.reason());
        assertTrue(engine.describeTenant("tenant-a").isEmpty(),
                "全部预占非法时不应创建租户计量状态，即未占用任何额度");

        Settlement noReservation = engine.settleReservation("s-missing", "tenant-a", "nope", 1L);
        Settlement negative = engine.settleReservation("s-neg", "tenant-a", "nope2", -1L);
        printSettle(noReservation);
        printSettle(negative);
        assertEquals(SettlementStatus.SETTLEMENT_NOT_FOUND, noReservation.status());
        assertEquals(SettlementStatus.SETTLEMENT_INVALID, negative.status());
        assertFalse(noReservation.effective());

        // 配置不可用：预占 fail-closed 明确拒绝；结算返回独立的降级状态，可安全重试
        policies.breakConfig();
        Reservation degraded = engine.reserve("r-down", "tenant-a", "job-down", 1L);
        Settlement settleDown = engine.settleReservation("s-down", "tenant-a", "job-down", 1L);
        printReserve(degraded);
        printSettle(settleDown);
        assertEquals(DecisionReason.DEGRADED_DENY, degraded.reason());
        assertEquals(503, degraded.reason().getHttpStatus());
        assertEquals(SettlementStatus.SETTLEMENT_DEGRADED, settleDown.status());
        assertFalse(settleDown.effective());
    }

    @Test
    void ordinaryOneShotPathStillWorksWithoutAnyReservation() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(100, 3, 60)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 1000);
        QuotaLimitEngine engine = engine(policies, clock, audit, false);

        for (int i = 0; i < 3; i++) {
            Decision d = engine.evaluate("plain-" + i, "tenant-a", null);
            assertEquals(DecisionReason.ALLOWED, d.reason());
            assertEquals(2 - i, d.quotaRemaining(), "无预占时剩余口径与老逻辑一致");
        }
        Decision out = engine.evaluate("plain-out", "tenant-a", null);
        System.out.printf("[老路径回归] requestId=%s reason=%s quotaRemain=%d%n        依据: %s%n",
                out.requestId(), out.reason(), out.quotaRemaining(), out.detail());
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, out.reason());
        assertEquals(0L, held(engine), "从未预占则占用恒为 0");
        assertEquals(3L, used(engine));
    }
}
