package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.AuditEvent;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.ReservationReaper;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预占超时「自动释放」专项测试。覆盖的业务：
 * <ol>
 *   <li>预占后不再发任何业务请求，到点后台巡检自动归零，额度回到窗口，并留下一条可区分的
 *       独立释放记录（哪笔、首次请求、放回多少、何时到期/释放、谁触发）；</li>
 *   <li>释放后迟到结算被明确拒绝(409)、不重复计费；已正常结算的业务不被误伤、重复结算只生效一次；</li>
 *   <li>并发：自动释放与同时进来的预占/结算/普通判定不互相干扰，不重复释放、不超发、视图自洽；</li>
 *   <li>管理端查看（无后续请求、无巡检）同样看到自洽的用量，并顺带留下释放痕迹；</li>
 *   <li>真实后台线程（ReservationReaper）按节奏自动释放；间隔 &lt;=0 时关闭后台巡检。</li>
 * </ol>
 */
class ReservationReaperTest {

    private static final String TENANT = "tenant-reaper";

    private void banner(String name) {
        System.out.println();
        System.out.println("====== 业务场景: " + name + " ======");
    }

    private void printView(String tag, Map<String, Object> view) {
        System.out.printf("[%s] quotaUsed=%s 在途笔数=%s 在途占用=%s windowStart=%s%n",
                tag, view.get("quotaUsed"), view.get("activeReservationCount"),
                view.get("activeReservedAmount"), view.get("windowStartEpochMilli"));
    }

    private QuotaLimitEngine engine(TestPolicies policies, MutableClock clock, long ttl) {
        return new QuotaLimitEngine(policies, new DefaultAuditLogger(clock, 5000), clock,
                false, 600_000, ttl);
    }

    private List<AuditEvent> expiredEvents(DefaultAuditLogger audit) {
        return audit.recentEvents().stream()
                .filter(e -> e.kind() == AuditEvent.Kind.RESERVATION_EXPIRED)
                .toList();
    }

    private Map<String, Object> view(QuotaLimitEngine engine) {
        return engine.describeTenant(TENANT).orElseThrow();
    }

    private long quotaUsed(QuotaLimitEngine engine) {
        return ((Number) view(engine).get("quotaUsed")).longValue();
    }

    private long activeReserved(QuotaLimitEngine engine) {
        return ((Number) view(engine).get("activeReservedAmount")).longValue();
    }

    private TenantPolicy policy(long quota) {
        // 速率给足（避免速率闸门干扰），配额窗口 3600s（测试内不滚动），窗口用量由测试控制
        return new TenantPolicy(TENANT, 100_000, 1000.0, quota, 3600);
    }

    /** 1. 预占后不再发任何请求：到点巡检自动归零、额度回窗口、留下唯一且可区分的释放记录。 */
    @Test
    void expiresAutomaticallyWithoutAnyFollowUpRequestAndLeavesTraceableRecord() {
        banner("预占后无后续请求，到点自动释放并留痕");
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(10)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 5000);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false,
                600_000, 30_000);

        ReservationResult hold = engine.reserve("auto-req", TENANT, "auto-key", 6);
        assertEquals(DecisionReason.RESERVED, hold.reason());
        printView("预占6后", view(engine));
        assertEquals(6L, quotaUsed(engine));
        assertEquals(6L, activeReserved(engine));

        // 未到 TTL：什么都不发生（无任何业务请求，只跑巡检）
        clock.advanceMillis(29_999);
        assertEquals(0, engine.runReaperPass(), "未到 TTL 不得提前释放");
        assertEquals(6L, quotaUsed(engine), "未到期占用必须仍计入已用量");
        assertEquals(6L, activeReserved(engine));

        // 到点（now == expiresAt 即失效，确定边界）：无需任何后续业务请求
        clock.advanceMillis(1);
        int released = engine.runReaperPass();
        assertEquals(1, released, "到点后最近一轮巡检必须自动释放该笔占用");

        printView("到点巡检后", view(engine));
        assertEquals(0L, quotaUsed(engine), "占用退回当前窗口，已用量必须归零");
        assertEquals(0L, activeReserved(engine), "在途占用必须归零");

        // 释放记录：唯一一条，且能说清是哪笔、首次请求、放回多少、谁触发
        List<AuditEvent> events = expiredEvents(audit);
        assertEquals(1, events.size(), "本次自动释放必须恰好留下一条可区分的记录");
        AuditEvent ev = events.get(0);
        System.out.println("[释放记录] " + ev.kind() + " requestId=" + ev.requestId()
                + " reason=" + ev.reason() + " basis=" + ev.basis());
        assertEquals(QuotaLimitEngine.REAPER_REQUEST_ID, ev.requestId(),
                "记录应标明由后台定时巡检触发");
        assertEquals(DecisionReason.RESERVATION_EXPIRED, ev.reason());
        assertTrue(ev.basis().contains("auto-key"), "记录需指明是哪笔预占");
        assertTrue(ev.basis().contains("auto-req"), "记录需指明首次请求 requestId");
        assertTrue(ev.basis().contains("占用=6"), "记录需指明放回额度");
        assertTrue(ev.basis().contains("退回当前窗口"));
        assertTrue(ev.basis().contains("后台定时巡检"));

        // 巡检幂等：再跑一轮不得重复释放、不得重复记审计
        assertEquals(0, engine.runReaperPass());
        assertEquals(1, expiredEvents(audit).size(), "同一笔占用不得被释放两次");

        // 额度确实回到窗口：新的 10 单位预占可以整笔放下（释放前只能再放 4）
        ReservationResult reuse = engine.reserve("reuse-req", TENANT, "reuse-key", 10);
        System.out.printf("[额度复用] reason=%s quotaRemain=%d 依据=%s%n",
                reuse.reason(), reuse.quotaRemaining(), reuse.detail());
        assertEquals(DecisionReason.RESERVED, reuse.reason(), "释放后额度必须真正回到可用窗口");
        assertEquals(0L, reuse.quotaRemaining());
    }

    /** 2. 自动释放后迟到结算被明确拒绝、不重复计费；已结算的不受超时误伤、重复结算只生效一次。 */
    @Test
    void lateSettlementRejectedAfterExpiryWhileSettledBusinessUnaffected() {
        banner("释放后迟到结算被拒(409)不重复计费；已结算不被误伤");
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(10)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 5000);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false,
                600_000, 30_000);

        // A 预占 6（将超时）；B 预占 3 并正常结算为真实 2（退回1）
        ReservationResult a = engine.reserve("late-a", TENANT, "hold-a", 6);
        ReservationResult b = engine.reserve("late-b", TENANT, "hold-b", 3);
        ReservationResult bSettle = engine.settle("late-b-s", TENANT, "hold-b", 2);
        assertEquals(DecisionReason.RESERVED, a.reason());
        assertEquals(DecisionReason.SETTLED, bSettle.reason());
        printView("A占6+B结算2后", view(engine));
        assertEquals(8L, quotaUsed(engine), "A 的6 在途 + B 已结算 2 = 8");

        // 只跑巡检（无业务请求）让 A 到点自动释放；B 已结算不受影响
        clock.advanceMillis(30_000);
        assertEquals(1, engine.runReaperPass());
        printView("A 到点自动释放后", view(engine));
        assertEquals(2L, quotaUsed(engine), "A 的6退回，仅剩 B 已结算的2");
        assertEquals(0L, activeReserved(engine), "已无任何在途占用");

        // A 迟到结算：明确拒绝 409，绝不二次计费
        ReservationResult lateA = engine.settle("late-a-s", TENANT, "hold-a", 6);
        System.out.printf("[迟到结算A] reason=%s http=%d 依据=%s%n",
                lateA.reason(), lateA.reason().getHttpStatus(), lateA.detail());
        assertEquals(DecisionReason.RESERVATION_EXPIRED, lateA.reason());
        assertFalse(lateA.allowed());
        assertEquals(409, lateA.reason().getHttpStatus());
        assertEquals(2L, quotaUsed(engine), "迟到结算被拒，窗口用量不得变化（不重复计费）");
        // 再次迟到结算仍是同样的拒绝，而不是“找不到”
        assertEquals(DecisionReason.RESERVATION_EXPIRED,
                engine.settle("late-a-s2", TENANT, "hold-a", 6).reason());

        // B 已正常结算：重复结算只能幂等重放，返回首次真实用量，不改变用量
        ReservationResult bReplay = engine.settle("late-b-s2", TENANT, "hold-b", 9);
        System.out.printf("[已结算B重复结算] reason=%s settled=%d 依据=%s%n",
                bReplay.reason(), bReplay.settledAmount(), bReplay.detail());
        assertEquals(DecisionReason.RESERVATION_REPLAY, bReplay.reason());
        assertEquals(2L, bReplay.settledAmount(), "重放必须返回首次生效的真实用量2");
        assertEquals(2L, quotaUsed(engine), "已结算业务不被超时机制误伤、重复结算不多扣");
        assertEquals(1, expiredEvents(audit).size(), "只有 A 一条释放记录，B 未被释放");
    }

    /** 3. 并发：自动释放与同时进来的判定不重复释放、不超发、已用量与在途始终自洽。 */
    @Test
    void concurrentSweepAndRequestsNeverDoubleReleaseNorOvershoot() throws Exception {
        banner("并发：巡检释放 × 新预占 × 普通请求 × 迟到结算");
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        final long limit = 10;
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(limit)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 5000);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false,
                600_000, 30_000);

        // 受害占用 6，到点后应被释放且只释放一次
        ReservationResult victim = engine.reserve("c-victim", TENANT, "victim", 6);
        assertEquals(DecisionReason.RESERVED, victim.reason());
        assertEquals(6L, quotaUsed(engine));

        clock.advanceMillis(30_000); // 到点

        int workers = 16;
        int rounds = 40;
        ExecutorService pool = Executors.newFixedThreadPool(workers + 1);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ReservationResult> outcomes = new ConcurrentLinkedQueue<>();
        AtomicInteger allowedReserves = new AtomicInteger();
        AtomicInteger deniedReserves = new AtomicInteger();
        AtomicInteger allowedPlain = new AtomicInteger();

        // 一半线程反复跑巡检（制造“释放与判定同时发生”的竞态）
        for (int w = 0; w < workers / 2; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        engine.runReaperPass();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        // 另一半线程混合发起：新预占（2）、普通请求（1）、受害占用的迟到结算
        for (int w = 0; w < workers / 2; w++) {
            final int idx = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        switch (i % 4) {
                            case 0 -> {
                                ReservationResult r = engine.reserve("c-r-" + idx + "-" + i,
                                        TENANT, "new-" + idx + "-" + i, 2);
                                outcomes.add(r);
                                if (r.reason() == DecisionReason.RESERVED) {
                                    allowedReserves.incrementAndGet();
                                } else if (r.reason() == DecisionReason.QUOTA_EXHAUSTED) {
                                    deniedReserves.incrementAndGet();
                                }
                            }
                            case 1 -> {
                                if (engine.evaluate("c-p-" + idx + "-" + i, TENANT,
                                        null).reason() == DecisionReason.ALLOWED) {
                                    allowedPlain.incrementAndGet();
                                }
                            }
                            case 2 -> outcomes.add(engine.settle("c-v-s-" + idx + "-" + i,
                                    TENANT, "victim", 6));
                            default -> engine.describeTenant(TENANT); // 管理端并发查看
                        }
                        // 每轮都校验视图自洽：已用量必须 >= 在途，且在窗口不滚动时
                        // quotaUsed 绝不允许超过上限（已结算补扣场景本测试不涉及）
                        Map<String, Object> v = view(engine);
                        long used = ((Number) v.get("quotaUsed")).longValue();
                        long held = ((Number) v.get("activeReservedAmount")).longValue();
                        if (used < held || used > limit) {
                            throw new AssertionError("用量不自洽或超发: used=" + used
                                    + " held=" + held + " limit=" + limit);
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发任务必须在限定时间内结束");
        if (!errors.isEmpty()) {
            errors.forEach(Throwable::printStackTrace);
        }
        assertTrue(errors.isEmpty(), "并发过程中不得出现用量不自洽/超发/异常");

        // 受害占用必须只被释放一次（巡检或迟到结算中的先到者触发其一）
        List<AuditEvent> victimExpired = expiredEvents(audit).stream()
                .filter(e -> e.basis().contains("victim")).toList();
        System.out.printf("[并发结果] 释放记录(victim)=%d 放行新预占=%d 拒绝新预占=%d 放行普通=%d%n",
                victimExpired.size(), allowedReserves.get(), deniedReserves.get(),
                allowedPlain.get());
        printView("并发结束后", view(engine));
        assertEquals(1, victimExpired.size(), "同一笔受害占用不得被释放两次");

        // 受害占用的迟到结算，无论多少次，只能得到 EXPIRED，绝不产生 SETTLED（不重复计费）
        long victimSettled = outcomes.stream()
                .filter(r -> "victim".equals(r.reservationKey()))
                .filter(r -> r.reason() == DecisionReason.SETTLED).count();
        long victimExpiredCnt = outcomes.stream()
                .filter(r -> "victim".equals(r.reservationKey()))
                .filter(r -> r.reason() == DecisionReason.RESERVATION_EXPIRED).count();
        assertEquals(0L, victimSettled, "已释放占用的迟到结算绝不能再真正计费");
        assertTrue(victimExpiredCnt > 0, "至少一次迟到结算应被明确拒绝为 RESERVATION_EXPIRED");

        // 最终不变式：所有在途占用（新预占×2）都必须计入已用量，且总量不超过上限
        long used = quotaUsed(engine);
        long held = activeReserved(engine);
        assertTrue(held <= used, "在途占用必须计入已用量");
        assertTrue(used <= limit, "任何放行/释放组合都不得让窗口用量超过上限");
        System.out.printf("[最终自洽] 已用=%d 在途=%d 上限=%d（在途全部计入已用且未超发）%n",
                used, held, limit);
    }

    /** 4. 管理端查看（无业务请求、无巡检）到点同样看到自洽用量，并顺带留下释放痕迹。 */
    @Test
    void adminViewIsSelfConsistentAfterExpiryWithoutAnyRequest() {
        banner("无请求/无巡检时管理端查看：已用量与在途自洽并留痕");
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(10)));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 5000);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false,
                600_000, 30_000);

        engine.reserve("admin-a", TENANT, "hold", 7);
        printView("预占7后", view(engine));

        clock.advanceMillis(30_000); // 到点，但不跑巡检、不发任何业务请求
        Map<String, Object> v = view(engine); // 仅管理员查看
        printView("管理员到点查看", v);
        assertEquals(0L, ((Number) v.get("quotaUsed")).longValue(),
                "已用量必须随到期占用释放而回落，不能仍显示被占着");
        assertEquals(0L, ((Number) v.get("activeReservedAmount")).longValue(),
                "在途占用为 0，二者必须自洽");
        assertEquals(0, ((Number) v.get("activeReservationCount")).longValue());

        List<AuditEvent> events = expiredEvents(audit);
        assertEquals(1, events.size(), "管理端查看触发的释放也应留下一条独立记录");
        assertTrue(events.get(0).requestId().startsWith(QuotaLimitEngine.ADMIN_SWEEP_REQUEST_ID),
                "记录应标明由管理端查看触发");
        assertTrue(events.get(0).basis().contains("hold"));
        System.out.println("[管理端释放记录] " + events.get(0).requestId() + " "
                + events.get(0).basis());
    }

    /** 5. 真实后台线程：启动后按节奏自动释放，无需任何手动调用或业务请求。 */
    @Test
    void backgroundReaperReleasesOnSchedule() throws Exception {
        banner("真实后台线程 ReservationReaper 按节奏自动释放");
        // MutableClock 不会自己走，无法验证“按真实时间周期触发”，因此这里使用系统时钟：
        // TTL 设为 300ms，预占后不发任何业务请求，只让后台线程按 50ms 节奏巡检并真实睡眠等待。
        DefaultAuditLogger audit = new DefaultAuditLogger(
                com.github.h3yow.tenantquotagatewayjava.limit.Clock.system(), 5000);
        QuotaLimitEngine engine = new QuotaLimitEngine(
                new TestPolicies(TestPolicies.snapshot(1,
                        new TenantPolicy(TENANT, 100_000, 1000.0, 10, 3600))),
                audit, com.github.h3yow.tenantquotagatewayjava.limit.Clock.system(),
                false, 600_000, 300);

        ReservationReaper reaper = new ReservationReaper(engine, 50);
        ReservationResult reserved = engine.reserve("bg-req", TENANT, "bg-key", 5);
        assertEquals(DecisionReason.RESERVED, reserved.reason());
        printView("后台预占5后", view(engine));
        assertEquals(5L, ((Number) view(engine).get("quotaUsed")).longValue());

        reaper.start();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                    && activeReserved(engine) != 0L) {
                Thread.sleep(25);
            }
        } finally {
            reaper.stop();
        }
        Map<String, Object> after = view(engine);
        printView("后台线程自动释放后", after);
        assertEquals(0L, ((Number) after.get("activeReservedAmount")).longValue(),
                "无业务请求，后台线程也应在 TTL 后释放在途占用");
        assertEquals(0L, ((Number) after.get("quotaUsed")).longValue(),
                "已用量应随后台自动释放回落");
        List<AuditEvent> events = expiredEvents(audit).stream()
                .filter(e -> e.basis().contains("bg-key")).toList();
        assertEquals(1, events.size(), "后台自动释放应留下恰好一条可区分记录");
        assertEquals(QuotaLimitEngine.REAPER_REQUEST_ID, events.get(0).requestId());
        assertFalse(reaper.isRunning(), "stop() 后巡检器应已停止");
        System.out.println("[后台释放记录] " + events.get(0).basis());
    }

    /** 6. 间隔 &lt;=0：不启动后台巡检（关闭开关），业务路径仍正常工作。 */
    @Test
    void reaperDisabledWhenIntervalNonPositive() {
        banner("配置 reservation-reaper-interval-millis<=0 时关闭后台巡检");
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, policy(10)));
        QuotaLimitEngine engine = engine(policies, clock, 30_000);
        ReservationReaper reaper = new ReservationReaper(engine, 0);
        reaper.start();
        assertFalse(reaper.isRunning(), "间隔<=0 时不应启动后台线程");
        ReservationResult r = engine.reserve("off-req", TENANT, "off-key", 4);
        assertEquals(DecisionReason.RESERVED, r.reason(), "关闭后台巡检不影响正常预占");
        reaper.stop();
        System.out.println("[关闭巡检] 后台线程未启动，业务路径照常工作；请求/管理端的惰性清理保留");
    }
}
