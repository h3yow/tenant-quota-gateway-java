package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.AuditEvent;
import com.github.h3yow.tenantquotagatewayjava.limit.Decision;
import com.github.h3yow.tenantquotagatewayjava.limit.DecisionReason;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.MutableClock;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常链路下降级行为测试：配置不可用时 fail-closed/fail-open 结果确定、原因可区分，
 * 恢复后判定回到正常；每次降级都有审计记录。
 */
class DegradationTest {

    private void print(Decision d) {
        System.out.printf("[降级] requestId=%s tenant=%s reason=%s httpStatus=%d%n        依据: %s%n",
                d.requestId(), d.tenantId(), d.reason(), d.reason().getHttpStatus(), d.detail());
    }

    @Test
    void failClosedWhenConfigUnavailableThenRecovers() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        TenantPolicy a = new TenantPolicy("tenant-a", 5, 1.0, 5, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 500);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, false, 60_000);

        Decision normal = engine.evaluate("req-ok", "tenant-a", null);
        print(normal);
        assertEquals(DecisionReason.ALLOWED, normal.reason());

        // 配置变为不可用：必须明确拒绝，原因 DEGRADED_DENY，503，与限流/配额拒绝可区分
        policies.breakConfig();
        Decision denied1 = engine.evaluate("req-down-1", "tenant-a", null);
        Decision denied2 = engine.evaluate("req-down-2", "tenant-a", null);
        print(denied1);
        print(denied2);
        assertFalse(denied1.allowed());
        assertEquals(DecisionReason.DEGRADED_DENY, denied1.reason());
        assertEquals(503, denied1.reason().getHttpStatus());
        assertEquals(DecisionReason.DEGRADED_DENY, denied2.reason());
        assertTrue(denied1.detail().contains("fail-closed"), "判定依据需说明降级语义");

        // 配置恢复：判定恢复正常，且降级期间用量状态未被破坏（之前只用了 1）
        policies.fixConfig(TestPolicies.snapshot(1, a));
        Decision recovered = engine.evaluate("req-recover", "tenant-a", null);
        print(recovered);
        assertEquals(DecisionReason.ALLOWED, recovered.reason());
        assertEquals(3L, recovered.quotaRemaining(), "降级期间不得凭空扣减用量");

        List<AuditEvent> degradations = audit.recentEvents().stream()
                .filter(e -> e.kind() == AuditEvent.Kind.DEGRADATION).toList();
        assertEquals(2, degradations.size(), "每次降级拒绝都必须审计");
        degradations.forEach(e -> System.out.printf("[审计-降级] seq=%d tenant=%s reason=%s 依据=%s%n",
                e.sequence(), e.tenantId(), e.reason(), e.basis()));
    }

    @Test
    void failOpenAllowsWithDistinctBasisAndAudits() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        TenantPolicy a = new TenantPolicy("tenant-a", 1, 1.0, 1, 60);
        TestPolicies policies = new TestPolicies(TestPolicies.snapshot(1, a));
        DefaultAuditLogger audit = new DefaultAuditLogger(clock, 500);
        QuotaLimitEngine engine = new QuotaLimitEngine(policies, audit, clock, true, 60_000);

        policies.breakConfig();
        Decision opened = engine.evaluate("req-open", "tenant-a", null);
        print(opened);
        // fail-open 放行，但原因仍是可解释的 ALLOWED 且依据明确标注降级放行
        assertTrue(opened.allowed());
        assertTrue(opened.detail().contains("fail-open 放行"));

        // 与正常放行区分：缺租户属于请求自身错误，即使 fail-open 也必须拒绝
        Decision missing = engine.evaluate("req-missing", " ", null);
        print(missing);
        assertEquals(DecisionReason.MISSING_TENANT, missing.reason());

        List<AuditEvent> degradations = audit.recentEvents().stream()
                .filter(e -> e.kind() == AuditEvent.Kind.DEGRADATION).toList();
        assertEquals(1, degradations.size(), "fail-open 放行也要留下降级审计");
    }
}
