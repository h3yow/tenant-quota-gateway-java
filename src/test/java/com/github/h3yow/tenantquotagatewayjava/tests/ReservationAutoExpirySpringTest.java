package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.AuditEvent;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.ReservationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证真实定时调度器：把 TTL 与扫描间隔调小，预占后<b>不再发任何请求</b>，
 * 等待后台 {@code ReservationExpiryScheduler} 自动释放——额度回落、在途归零、
 * 审计中出现可区分的 RESERVATION_EXPIRED 记录。
 */
@SpringBootTest(properties = {
        "tenant-quota.reservation-ttl-millis=300",
        "tenant-quota.reservation-sweep-interval-millis=50"
})
class ReservationAutoExpirySpringTest {

    @Autowired
    private QuotaLimitEngine engine;

    @Autowired
    private DefaultAuditLogger auditLogger;

    @Test
    void schedulerReleasesExpiredReservationWithoutAnyFurtherRequest() throws Exception {
        // tenant-http: 配额上限 5（见 policies.json）
        ReservationResult hold = engine.reserve("spring-auto-1", "tenant-http",
                "spring-auto-key", 2);
        assertEquals(DecisionReason.RESERVED, hold.reason());
        System.out.printf("[调度器-预占] 占用=2, 之后不再发任何请求, 等待后台扫描(TTL=300ms, 间隔=50ms)%n");

        // 关键：等待期间不触碰引擎任何判定/视图接口（避免顺带扫描代劳），
        // 只观察审计日志——释放必须由后台定时调度器(auto-expiry-sweeper)完成
        AuditEvent expiry = awaitSchedulerRelease(5_000);
        System.out.printf("[调度器-审计] requestId=%s basis=%s%n",
                expiry.requestId(), expiry.basis());
        assertEquals("auto-expiry-sweeper", expiry.requestId(),
                "无后续请求时，释放必须由后台定时扫描触发");
        assertTrue(expiry.basis().contains("预占键=spring-auto-key"), "记录要能定位是哪一笔");
        assertTrue(expiry.basis().contains("占用=2"), "记录要说明放回了多少");
        assertTrue(expiry.basis().contains("后台定时扫描"), "记录要能与请求顺带扫描区分");

        // 释放已发生后再看管理端视图：额度回落、在途归零，两个数自洽
        Map<String, Object> view = engine.describeTenant("tenant-http").orElseThrow();
        System.out.printf("[调度器-释放] quotaUsed=%s activeReserved=%s activeCount=%s%n",
                view.get("quotaUsed"), view.get("activeReservedAmount"),
                view.get("activeReservationCount"));
        assertEquals(0L, ((Number) view.get("quotaUsed")).longValue(),
                "超时后无需后续请求，已用量必须自动回落");
        assertEquals(0L, ((Number) view.get("activeReservedAmount")).longValue());
        assertEquals(0L, ((Number) view.get("activeReservationCount")).longValue());

        // 迟到结算被明确拒绝，不重复计费
        ReservationResult late = engine.settle("spring-auto-late", "tenant-http",
                "spring-auto-key", 2);
        System.out.printf("[调度器-迟到结算] reason=%s 依据=%s%n", late.reason(), late.detail());
        assertEquals(DecisionReason.RESERVATION_EXPIRED, late.reason());
        assertEquals(0L, ((Number) engine.describeTenant("tenant-http").orElseThrow()
                .get("quotaUsed")).longValue(), "迟到结算不得重复计费");
    }

    /** 轮询审计日志直到后台调度器释放本笔预占（超时则断言失败）。 */
    private AuditEvent awaitSchedulerRelease(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            var hit = auditLogger.recentEvents().stream()
                    .filter(e -> e.kind() == AuditEvent.Kind.RESERVATION_EXPIRED)
                    .filter(e -> e.basis() != null && e.basis().contains("spring-auto-key"))
                    .findFirst();
            if (hit.isPresent()) {
                return hit.get();
            }
            Thread.sleep(25);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("等待后台调度器自动释放超时：未出现 spring-auto-key 的释放审计");
    }
}
