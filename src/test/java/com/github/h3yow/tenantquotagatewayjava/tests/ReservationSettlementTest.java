package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.Decision;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.ReservationResult;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预占（reserve）与结算（settle）语义测试：
 * 并发占用不超额、多退少补、重复/乱序结算只生效一次、占用超时释放、
 * 策略刷新时在途占用迁移，以及不涉及预占的既有路径不回归。
 */
class ReservationSettlementTest {

    private static final String TENANT = "tenant-job";

    private void print(ReservationResult r) {
        System.out.printf("[预占/结算] requestId=%s tenant=%s key=%s reason=%s status=%s "
                        + "reserve=%d settle=%d quotaRemain=%d%n        依据: %s%n",
                r.requestId(), r.tenantId(), r.reservationKey(), r.reason(), r.status(),
                r.reservedAmount(), r.settledAmount(), r.quotaRemaining(), r.detail());
    }

    private QuotaLimitEngine engine(TestPolicies policies, MutableClock clock, long ttl) {
        return new QuotaLimitEngine(policies, new DefaultAuditLogger(clock, 2000), clock,
                false, 60_000, ttl);
    }

    private long quotaUsed(QuotaLimitEngine engine) {
        return ((Number) engine.describeTenant(TENANT).orElseThrow().get("quotaUsed"))
                .longValue();
    }

    private long activeReserved(QuotaLimitEngine engine) {
        return ((Number) engine.describeTenant(TENANT).orElseThrow().get("activeReservedAmount"))
                .longValue();
    }

    /** 1. 并发占用：同一租户多线程同时预占，放行总量（占用之和）严格不超过配额上限。 */
    @Test
    void concurrentReservationsNeverExceedLimit() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        // 速率给足，配额 100；每笔预占 6，故最多放行 16 笔（96），第 17 笔放不下
        TenantPolicy p = new TenantPolicy(TENANT, 100_000, 0.0, 100, 300);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, p));
        QuotaLimitEngine engine = engine(policies, clock, 120_000);

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReservationResult> results = new ConcurrentLinkedQueue<>();

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
                results.add(engine.reserve("job-reserve-" + idx, TENANT,
                        "job-key-" + idx, 6));
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long reservedCount = results.stream()
                .filter(r -> r.reason() == DecisionReason.RESERVED).count();
        long denied = results.stream()
                .filter(r -> r.reason() == DecisionReason.QUOTA_EXHAUSTED).count();
        long totalHeld = results.stream().filter(r -> r.allowed())
                .mapToLong(ReservationResult::reservedAmount).sum();

        results.stream().filter(ReservationResult::allowed).limit(3).forEach(this::print);
        results.stream().filter(r -> !r.allowed()).findFirst().ifPresent(r -> {
            print(r);
            assertTrue(r.detail().contains("在途占用"));
        });
        System.out.printf("[并发占用] 总请求=%d 预占成功=%d 配额拒绝=%d 实际占用总量=%d (上限100)%n",
                threads, reservedCount, denied, totalHeld);

        assertEquals(16L, reservedCount, "预估6×16=96，第17笔放不下，成功笔数必须严格=16");
        assertEquals(threads - 16L, denied, "其余必须是明确可区分的配额拒绝");
        assertEquals(96L, totalHeld, "并发占用总量不得超过上限");
        assertEquals(96L, quotaUsed(engine), "窗口已用量必须等于全部在途占用");
        assertEquals(96L, activeReserved(engine), "全部成功预占仍应在途");
    }

    /** 2. 多退少补：真实用量小于预占退回，大于预占补扣。 */
    @Test
    void settleRefundsOverReservationAndChargesTheShortfall() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TenantPolicy p = new TenantPolicy(TENANT, 100, 100.0, 10, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, p));
        QuotaLimitEngine engine = engine(policies, clock, 120_000);

        // 预占 8（占用 8/10）
        ReservationResult reserve = engine.reserve("job-1-req", TENANT, "job-1", 8);
        print(reserve);
        assertEquals(DecisionReason.RESERVED, reserve.reason());
        assertEquals(2L, reserve.quotaRemaining());
        assertEquals(8L, quotaUsed(engine));

        // 别的请求只能使用剩余 2
        ReservationResult other = engine.reserve("job-other", TENANT, "job-other", 3);
        print(other);
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, other.reason(), "被占住的量不能被重复使用");
        ReservationResult small = engine.reserve("job-small", TENANT, "job-small", 2);
        assertEquals(DecisionReason.RESERVED, small.reason());

        // 真实只用了 5 -> 退回 3，窗口用量回到 5+2=7，剩余回到 3
        ReservationResult refund = engine.settle("job-1-settle", TENANT, "job-1", 5);
        print(refund);
        assertEquals(DecisionReason.SETTLED, refund.reason());
        assertEquals(3L, refund.quotaRemaining(), "多占退回后剩余应恢复到3");
        assertTrue(refund.detail().contains("多占退回 3"));
        assertEquals(2L, activeReserved(engine), "job-1 已结算不再在途，仅剩 job-small 的2");

        // 第二笔：预占 1，真实用了 3 -> 补扣 2
        ReservationResult r2 = engine.reserve("job-2-req", TENANT, "job-2", 1);
        assertEquals(DecisionReason.RESERVED, r2.reason());
        ReservationResult extra = engine.settle("job-2-settle", TENANT, "job-2", 3);
        print(extra);
        assertEquals(DecisionReason.SETTLED, extra.reason());
        assertTrue(extra.detail().contains("少占补扣 2"));
    }

    /** 3. 重复预占 + 重复/乱序结算：同一笔业务只真正占用一次、只真正结算一次。 */
    @Test
    void duplicateAndOutOfOrderSettlementsApplyExactlyOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TenantPolicy p = new TenantPolicy(TENANT, 100, 100.0, 10, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, p));
        QuotaLimitEngine engine = engine(policies, clock, 120_000);

        ReservationResult first = engine.reserve("dup-r-1", TENANT, "dup-key", 4);
        ReservationResult again = engine.reserve("dup-r-2", TENANT, "dup-key", 4);
        ReservationResult third = engine.reserve("dup-r-3", TENANT, "dup-key", 4);
        print(first);
        print(again);
        assertEquals(DecisionReason.RESERVED, first.reason());
        assertEquals(DecisionReason.RESERVATION_REPLAY, again.reason());
        assertEquals(DecisionReason.RESERVATION_REPLAY, third.reason());
        assertEquals(4L, quotaUsed(engine), "重复预占不得二次占用");

        // 乱序/重复结算：首次按真实 2 结算（退 2），之后全部重放，不重复计费
        ReservationResult lateArriving = engine.settle("dup-s-2", TENANT, "dup-key", 9);
        ReservationResult realFirst = engine.settle("dup-s-1", TENANT, "dup-key", 2);
        ReservationResult replay = engine.settle("dup-s-3", TENANT, "dup-key", 2);
        print(lateArriving);
        print(realFirst);
        print(replay);
        assertEquals(DecisionReason.SETTLED, lateArriving.reason(), "先到的结算先生效");
        assertEquals(9L, lateArriving.settledAmount());
        assertEquals(DecisionReason.RESERVATION_REPLAY, realFirst.reason());
        assertEquals(DecisionReason.RESERVATION_REPLAY, replay.reason());
        assertEquals(9L, replay.settledAmount(), "重放返回首次生效的真实用量(先到的9)");
        assertTrue(replay.detail().contains("dup-s-2"), "重放依据需指出首次结算 requestId");
        assertEquals(9L, quotaUsed(engine), "无论提交几次结算，窗口只按一次真实用量计(9)");
        assertEquals(0L, activeReserved(engine), "已结算不再在途");
    }

    /** 3b. 并发同一键结算：高并发下也恰好只有一次真正生效，其余全部重放，窗口不被多扣。 */
    @Test
    void concurrentSettlementsForSameKeyApplyExactlyOnce() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TenantPolicy p = new TenantPolicy(TENANT, 100, 100.0, 100, 600);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, p));
        QuotaLimitEngine engine = engine(policies, clock, 120_000);

        ReservationResult held = engine.reserve("csv-reserve", TENANT, "csv-key", 50);
        assertEquals(DecisionReason.RESERVED, held.reason());
        assertEquals(50L, quotaUsed(engine));

        int threads = 60;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReservationResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            // 各线程声称的真实用量不同，证明只有先抢到锁的那一个数字生效
            long claimed = 20 + idx;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                results.add(engine.settle("csv-settle-" + idx, TENANT, "csv-key", claimed));
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long settled = results.stream()
                .filter(r -> r.reason() == DecisionReason.SETTLED).count();
        long replays = results.stream()
                .filter(r -> r.reason() == DecisionReason.RESERVATION_REPLAY).count();
        long winningAmount = results.stream()
                .filter(r -> r.reason() == DecisionReason.SETTLED)
                .mapToLong(ReservationResult::settledAmount).findFirst().orElse(-1);
        System.out.printf("[并发同键结算] 总结算=%d 真正生效=%d 幂等重放=%d 生效真实用量=%d%n",
                threads, settled, replays, winningAmount);

        assertEquals(1L, settled, "并发结算同一笔业务必须只生效一次");
        assertEquals(threads - 1L, replays, "其余并发结算必须是幂等重放");
        assertEquals(winningAmount, quotaUsed(engine),
                "窗口用量必须等于唯一生效的真实用量，不能被多扣");
        assertTrue(results.stream().allMatch(r -> r.settledAmount() == winningAmount),
                "所有重放必须返回同一个首次生效的真实用量");
    }

    /** 4. 占用超时：TTL 到期未结算自动释放，之后可再被使用；迟到结算被明确拒绝，已结算不被误伤。 */
    @Test
    void reservationTtlReleasesAndLateSettlementRejectedWithoutHarmingSettled() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TenantPolicy p = new TenantPolicy(TENANT, 100, 100.0, 10, 600);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, p));
        QuotaLimitEngine engine = engine(policies, clock, 30_000);

        // A 预占 6，B 预占 2 并立即按真实 2 结算
        ReservationResult a = engine.reserve("ttl-a", TENANT, "hold-a", 6);
        ReservationResult b = engine.reserve("ttl-b", TENANT, "hold-b", 2);
        ReservationResult bSettle = engine.settle("ttl-b-settle", TENANT, "hold-b", 2);
        assertEquals(DecisionReason.RESERVED, a.reason());
        assertEquals(DecisionReason.SETTLED, bSettle.reason());
        assertEquals(8L, quotaUsed(engine), "6 在途 + 2 已结算");

        // 推进超过 A 的 TTL(30s) 但未到窗口边界(600s)；借一次普通判定触发惰性扫描
        clock.advanceMillis(30_001);
        Decision trigger = engine.evaluate("ttl-trigger", TENANT, null);
        assertEquals(DecisionReason.ALLOWED, trigger.reason());
        System.out.printf("[超时释放] 触发扫描 requestId=%s 后 quotaRemain=%d 依据=%s%n",
                trigger.requestId(), trigger.quotaRemaining(), trigger.detail());
        assertEquals(7L, trigger.quotaRemaining(),
                "A 的6被释放(退回)，B 的2已结算保留，再扣1普通 -> 已用3，剩余7");

        // 超时后之前放不下的预占现在可以成功（额度确实退回）
        ReservationResult after = engine.reserve("ttl-after", TENANT, "hold-c", 6);
        print(after);
        assertEquals(DecisionReason.RESERVED, after.reason());

        // A 的迟到结算必须被拒绝，且不会对已释放的占用重复计费
        ReservationResult late = engine.settle("ttl-a-late", TENANT, "hold-a", 6);
        print(late);
        assertEquals(DecisionReason.RESERVATION_EXPIRED, late.reason());
        assertFalse(late.allowed());
        assertEquals(409, late.reason().getHttpStatus());
        assertTrue(late.detail().contains("到期自动释放"));

        // 已正常结算的 B 再来结算仍只是幂等重放，没有被超时机制误伤
        ReservationResult bReplay = engine.settle("ttl-b-replay", TENANT, "hold-b", 2);
        print(bReplay);
        assertEquals(DecisionReason.RESERVATION_REPLAY, bReplay.reason());
    }

    /** 5. 策略刷新时在途占用不丢失、判定不错乱，后续结算落到正确窗口与上限。 */
    @Test
    void inFlightReservationsSurviveRefreshAndSettleAgainstCurrentWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TenantPolicy v1 = new TenantPolicy(TENANT, 100, 100.0, 10, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, v1));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 2000);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false,
                60_000, 120_000);

        ReservationResult hold = engine.reserve("refresh-hold", TENANT, "rf-key", 6);
        assertEquals(DecisionReason.RESERVED, hold.reason());
        assertEquals(4L, hold.quotaRemaining());

        // 上限/窗口不变、仅速率参数变化 -> 当前窗口保留：6 占用必须仍在
        TenantPolicy v2 = new TenantPolicy(TENANT, 200, 200.0, 10, 60);
        policies.swap(TestPolicies.snapshot(2, v2));
        engine.reloadPolicies();
        ReservationResult stillHeld = engine.reserve("refresh-check", TENANT, "rf-other", 5);
        print(stillHeld);
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, stillHeld.reason(),
                "刷新后在途占用仍占着额度，5 放不下（仅剩4）");
        assertEquals(4L, stillHeld.quotaRemaining());

        // 在途业务结算，落在 v2 的窗口与上限上：真实 3 -> 退 3
        ReservationResult settled = engine.settle("refresh-settle", TENANT, "rf-key", 3);
        print(settled);
        assertEquals(DecisionReason.SETTLED, settled.reason());
        assertEquals(2L, settled.policyVersion(), "结算必须引用刷新后的策略版本");
        assertEquals(7L, settled.quotaRemaining(), "退回后已用3，剩余7");

        // 配额维度变化（上限 10 -> 20）：在途占用迁移到新窗口，用量不凭空消失
        ReservationResult hold2 = engine.reserve("refresh-hold2", TENANT, "rf-key2", 4);
        assertEquals(DecisionReason.RESERVED, hold2.reason());
        TenantPolicy v3 = new TenantPolicy(TENANT, 200, 200.0, 20, 60);
        policies.swap(TestPolicies.snapshot(3, v3));
        engine.reloadPolicies();
        assertEquals(4L, activeReserved(engine), "维度刷新后在途占用必须迁移保留");
        ReservationResult settleMigrated = engine.settle("refresh-settle2", TENANT,
                "rf-key2", 4);
        print(settleMigrated);
        assertEquals(DecisionReason.SETTLED, settleMigrated.reason());
        assertEquals(3L, settleMigrated.policyVersion());
        assertEquals(16L, settleMigrated.quotaRemaining(), "按新上限20计，已用4 -> 剩余16");
    }

    /** 6. 参数与引用类错误原因可区分。 */
    @Test
    void invalidArgumentsAndUnknownKeysHaveDistinctReasons() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TenantPolicy p = new TenantPolicy(TENANT, 100, 100.0, 10, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, p));
        QuotaLimitEngine engine = engine(policies, clock, 120_000);

        ReservationResult badEstimate = engine.reserve("bad-est", TENANT, "k", 0);
        ReservationResult missingKey = engine.settle("no-key", TENANT, " ", 1);
        ReservationResult notFound = engine.settle("ghost", TENANT, "never-reserved", 1);
        print(badEstimate);
        print(missingKey);
        print(notFound);
        assertEquals(DecisionReason.INVALID_ARGUMENT, badEstimate.reason());
        assertEquals(400, badEstimate.reason().getHttpStatus());
        assertEquals(DecisionReason.INVALID_ARGUMENT, missingKey.reason());
        assertEquals(DecisionReason.RESERVATION_NOT_FOUND, notFound.reason());
        assertEquals(404, notFound.reason().getHttpStatus());
        assertEquals(0L, quotaUsed(engine), "非法/未命中请求不得改动用量");

        // 已结算的键不能再用来发起新预占 -> 409 RESERVATION_CLOSED，与 NOT_FOUND 区分
        ReservationResult closed = engine.reserve("k-reuse", TENANT, "k-closed", 2);
        assertEquals(DecisionReason.RESERVED, closed.reason());
        engine.settle("k-closed-s", TENANT, "k-closed", 2);
        ReservationResult reuse = engine.reserve("k-reuse-2", TENANT, "k-closed", 2);
        print(reuse);
        assertEquals(DecisionReason.RESERVATION_CLOSED, reuse.reason());
        assertEquals(409, reuse.reason().getHttpStatus());
    }

    /** 7. 不涉及预占的既有一次性路径完全不回归。 */
    @Test
    void plainOneShotPathUnaffectedAndReservationsAreCountedAlongside() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        TenantPolicy p = new TenantPolicy(TENANT, 100, 100.0, 10, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, p));
        QuotaLimitEngine engine = engine(policies, clock, 120_000);

        // 普通请求照旧扣 1
        Decision plain1 = engine.evaluate("plain-1", TENANT, null);
        Decision plain2 = engine.evaluate("plain-2", TENANT, "idem-plain");
        Decision plain2Replay = engine.evaluate("plain-2r", TENANT, "idem-plain");
        assertEquals(DecisionReason.ALLOWED, plain1.reason());
        assertEquals(DecisionReason.ALLOWED, plain2.reason());
        assertEquals(DecisionReason.IDEMPOTENT_REPLAY, plain2Replay.reason());
        assertEquals(2L, quotaUsed(engine));

        // 预占 7 后，普通请求与预占共享同一上限：仅剩 1 个普通请求额度
        ReservationResult hold = engine.reserve("mixed-hold", TENANT, "mixed", 7);
        assertEquals(DecisionReason.RESERVED, hold.reason());
        Decision plain3 = engine.evaluate("plain-3", TENANT, null);
        Decision plain4 = engine.evaluate("plain-4", TENANT, null);
        System.out.printf("[混合负载] plain3=%s quotaRemain=%d; plain4=%s 依据=%s%n",
                plain3.reason(), plain3.quotaRemaining(), plain4.reason(), plain4.detail());
        assertEquals(DecisionReason.ALLOWED, plain3.reason());
        assertEquals(DecisionReason.QUOTA_EXHAUSTED, plain4.reason(),
                "普通请求必须把在途占用算进已用量，不能超发");
    }
}
