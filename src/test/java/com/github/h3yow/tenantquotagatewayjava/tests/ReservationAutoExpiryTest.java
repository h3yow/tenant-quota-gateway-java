package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.AuditEvent;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.ReservationResult;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预占超时<b>自动</b>释放测试：不依赖任何后续请求，由后台扫描入口
 * {@link QuotaLimitEngine#sweepExpiredReservations()}（生产中由定时调度器驱动）到点释放；
 * 释放后迟到结算被明确拒绝、已结算业务不受误伤；并发扫描与并发判定不重复释放、不超发。
 */
class ReservationAutoExpiryTest {

    private static final String TENANT = "tenant-auto-expiry";

    private DefaultAuditLogger audit;
    private QuotaLimitEngine engine;

    private QuotaLimitEngine newEngine(MutableClock clock, long quotaLimit, long ttlMillis) {
        TenantPolicy p = new TenantPolicy(TENANT, 100_000, 0.0, quotaLimit, 3600);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, p));
        audit = new DefaultAuditLogger(clock, 5000);
        engine = new QuotaLimitEngine(policies, audit, clock, false, 60_000, ttlMillis);
        return engine;
    }

    private Map<String, Object> view() {
        return engine.describeTenant(TENANT).orElseThrow();
    }

    private long quotaUsed() {
        return ((Number) view().get("quotaUsed")).longValue();
    }

    private long activeReserved() {
        return ((Number) view().get("activeReservedAmount")).longValue();
    }

    private List<AuditEvent> expiryEvents() {
        return audit.recentEvents().stream()
                .filter(e -> e.kind() == AuditEvent.Kind.RESERVATION_EXPIRED).toList();
    }

    /** 1. 预占后不再发任何请求：过 TTL 后由后台扫描自动归零，并留下可区分的释放记录。 */
    @Test
    void expiryHappensWithoutAnyFurtherRequest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        newEngine(clock, 10, 30_000);

        ReservationResult hold = engine.reserve("auto-r-1", TENANT, "auto-key-1", 6);
        assertEquals(DecisionReason.RESERVED, hold.reason());
        assertEquals(6L, quotaUsed());
        assertEquals(6L, activeReserved());
        System.out.printf("[自动释放-预占] key=auto-key-1 占用=6 quotaUsed=%d activeReserved=%d%n",
                quotaUsed(), activeReserved());

        // 关键：此后该租户没有任何请求。推进时间越过 TTL，直接调用后台扫描入口
        // （生产中由 ReservationExpiryScheduler 定时触发，这里手动调用同一入口验证语义）
        clock.advanceMillis(30_001);
        int released = engine.sweepExpiredReservations();
        System.out.printf("[自动释放-扫描] 无任何后续请求, 本次释放笔数=%d%n", released);
        assertEquals(1, released, "到期占用必须被后台扫描主动释放，不等后续请求");

        // 额度回到当前可用窗口：已用量回落、在途占用归零，两个数自洽
        assertEquals(0L, quotaUsed(), "释放后已用量必须回落");
        assertEquals(0L, activeReserved(), "释放后不应再有在途占用");

        // 留下一条可区分的释放记录：说得清是哪笔、放回了多少、由谁触发
        List<AuditEvent> events = expiryEvents();
        assertEquals(1, events.size(), "必须恰好留下一条超时释放审计");
        AuditEvent e = events.get(0);
        System.out.printf("[自动释放-审计] kind=%s requestId=%s basis=%s%n",
                e.kind(), e.requestId(), e.basis());
        assertEquals(AuditEvent.Kind.RESERVATION_EXPIRED, e.kind());
        assertEquals("auto-expiry-sweeper", e.requestId(), "后台扫描的释放要有可区分触发者标识");
        assertTrue(e.basis().contains("预占键=auto-key-1"), "记录要能定位是哪一笔");
        assertTrue(e.basis().contains("占用=6"), "记录要说明放回了多少");
        assertTrue(e.basis().contains("后台定时扫描"), "记录要能与请求顺带扫描区分");

        // 幂等：再扫多少次都不会重复释放、重复出账
        assertEquals(0, engine.sweepExpiredReservations(), "同一笔占用不得被释放两次");
        assertEquals(1, expiryEvents().size(), "重复扫描不得产生重复释放记录");
        assertEquals(0L, quotaUsed());

        // 释放的额度确实回到可用窗口：新的预占可以立即用上
        ReservationResult reuse = engine.reserve("auto-r-2", TENANT, "auto-key-2", 10);
        assertEquals(DecisionReason.RESERVED, reuse.reason(), "释放回的额度必须可被后续使用");
        System.out.printf("[自动释放-复用] 新预占10成功 quotaRemain=%d%n", reuse.quotaRemaining());
    }

    /** 2. 自动释放后：迟到结算被明确拒绝且不重复计费；已正常结算的业务不受误伤。 */
    @Test
    void lateSettlementRejectedAfterAutoExpiryAndSettledUnaffected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        newEngine(clock, 10, 30_000);

        // A 预占 6 不结算（将超时）；B 预占 2 并立即按真实 2 正常结算
        assertEquals(DecisionReason.RESERVED,
                engine.reserve("late-a", TENANT, "late-key-a", 6).reason());
        assertEquals(DecisionReason.RESERVED,
                engine.reserve("late-b", TENANT, "late-key-b", 2).reason());
        ReservationResult bSettled = engine.settle("late-b-settle", TENANT, "late-key-b", 2);
        assertEquals(DecisionReason.SETTLED, bSettled.reason());
        assertEquals(8L, quotaUsed(), "6 在途 + 2 已结算");

        // 越过 TTL，后台扫描只应释放 A；B 已结算为终态，不受超时逻辑误伤
        clock.advanceMillis(30_001);
        int released = engine.sweepExpiredReservations();
        assertEquals(1, released, "只有 A 一笔在途占用应被释放");
        assertEquals(2L, quotaUsed(), "A 的 6 退回后，仅剩 B 已结算的 2");
        assertEquals(0L, activeReserved());

        // A 迟到的结算：明确拒绝(409 RESERVATION_EXPIRED)，且不得对释放回的额度重复计费
        ReservationResult late = engine.settle("late-a-settle", TENANT, "late-key-a", 6);
        System.out.printf("[自动释放-迟到结算] reason=%s status=%s 依据=%s%n",
                late.reason(), late.status(), late.detail());
        assertEquals(DecisionReason.RESERVATION_EXPIRED, late.reason());
        assertFalse(late.allowed());
        assertEquals(409, late.reason().getHttpStatus());
        assertEquals(2L, quotaUsed(), "迟到结算不得重复计费，已用量必须保持 2");

        // B 已正常结算：重复提交仍只是幂等重放，生效的仍是首次结果，不多扣不少扣
        ReservationResult bReplay = engine.settle("late-b-replay", TENANT, "late-key-b", 2);
        assertEquals(DecisionReason.RESERVATION_REPLAY, bReplay.reason());
        assertEquals(2L, bReplay.settledAmount());
        assertEquals(2L, quotaUsed(), "已结算业务不受超时逻辑影响，重复结算只生效一次");

        // 管理端视角自洽：无在途占用，已用量恰为已结算的 2
        Map<String, Object> v = view();
        System.out.printf("[自动释放-管理端] quotaUsed=%s activeReserved=%s activeCount=%s%n",
                v.get("quotaUsed"), v.get("activeReservedAmount"),
                v.get("activeReservationCount"));
        assertEquals(2L, ((Number) v.get("quotaUsed")).longValue());
        assertEquals(0L, ((Number) v.get("activeReservedAmount")).longValue());
        assertEquals(0L, ((Number) v.get("activeReservationCount")).longValue());
    }

    /** 3. 并发：多线程扫描与同时到来的迟到结算/新预占互不干扰——不重复释放、不超发。 */
    @Test
    void concurrentSweepAndJudgmentsNoDoubleReleaseNoOverIssue() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        long limit = 100;
        newEngine(clock, limit, 30_000);

        // 先占满：10 笔 × 10 = 100
        for (int i = 0; i < 10; i++) {
            assertEquals(DecisionReason.RESERVED,
                    engine.reserve("conc-hold-" + i, TENANT, "conc-key-" + i, 10).reason());
        }
        assertEquals(100L, quotaUsed());

        // 越过 TTL 后同时开打：4 个扫描线程、10 个迟到结算线程、10 个新预占线程
        clock.advanceMillis(30_001);
        int sweepers = 4, lateSettlers = 10, newReservers = 10;
        int threads = sweepers + lateSettlers + newReservers;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReservationResult> settleResults = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ReservationResult> reserveResults = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> sweepRounds = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < sweepers; i++) {
            pool.submit(() -> {
                ready.countDown();
                await(start);
                for (int round = 0; round < 5; round++) {
                    sweepRounds.add(engine.sweepExpiredReservations());
                }
            });
        }
        for (int i = 0; i < lateSettlers; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                await(start);
                settleResults.add(engine.settle("conc-late-" + idx, TENANT,
                        "conc-key-" + idx, 10));
            });
        }
        for (int i = 0; i < newReservers; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                await(start);
                reserveResults.add(engine.reserve("conc-new-" + idx, TENANT,
                        "conc-new-key-" + idx, 10));
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        // 不重复释放：10 笔到期占用合计恰好释放一次，释放记录也恰好 10 条
        int totalReleased = sweepRounds.stream().mapToInt(Integer::intValue).sum();
        System.out.printf("[并发扫描] 扫描轮次=%d 累计释放=%d 释放审计条数=%d%n",
                sweepRounds.size(), totalReleased, expiryEvents().size());
        assertEquals(10, totalReleased, "并发扫描下同一批占用合计只能被释放一次");
        assertEquals(10, expiryEvents().size(), "每笔占用恰好一条释放记录，不得重复");

        // 迟到结算全部被明确拒绝，无一生效
        long settledCount = settleResults.stream()
                .filter(r -> r.reason() == DecisionReason.SETTLED).count();
        long expiredCount = settleResults.stream()
                .filter(r -> r.reason() == DecisionReason.RESERVATION_EXPIRED).count();
        System.out.printf("[并发扫描] 迟到结算: 生效=%d 被拒=%d%n", settledCount, expiredCount);
        assertEquals(0L, settledCount, "已超时占用的迟到结算不得生效");
        assertEquals(10L, expiredCount);

        // 不超发：新预占成功者合计占用 <= 释放回的 100；终态自洽
        long newHeld = reserveResults.stream()
                .filter(r -> r.reason() == DecisionReason.RESERVED)
                .mapToLong(ReservationResult::reservedAmount).sum();
        System.out.printf("[并发扫描] 新预占成功占用=%d quotaUsed=%d activeReserved=%d%n",
                newHeld, quotaUsed(), activeReserved());
        assertTrue(newHeld <= limit, "释放后放行总量不得超过上限");
        assertEquals(newHeld, quotaUsed(), "无结算生效时，已用量必须等于新在途占用");
        assertEquals(newHeld, activeReserved(), "已用量与在途占用必须自洽");
        assertTrue(quotaUsed() <= limit, "窗口已用量不得超过上限");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
