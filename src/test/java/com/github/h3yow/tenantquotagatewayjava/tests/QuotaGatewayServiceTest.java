package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.config.GatewayProperties;
import com.github.h3yow.tenantquotagatewayjava.quota.AuditEntry;
import com.github.h3yow.tenantquotagatewayjava.quota.AuditLog;
import com.github.h3yow.tenantquotagatewayjava.quota.Decision;
import com.github.h3yow.tenantquotagatewayjava.quota.PolicyRegistry;
import com.github.h3yow.tenantquotagatewayjava.quota.QuotaGatewayService;
import com.github.h3yow.tenantquotagatewayjava.quota.QuotaLedger;
import com.github.h3yow.tenantquotagatewayjava.quota.RejectionReason;
import com.github.h3yow.tenantquotagatewayjava.quota.TenantPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
 * 多租户限流与配额保护的核心场景测试。
 * 每个用例都会打印请求标识与判定依据，便于人工核对可解释性。
 */
class QuotaGatewayServiceTest {

    private AuditLog auditLog;
    private QuotaLedger ledger;
    private PolicyRegistry registry;
    private GatewayProperties properties;
    private QuotaGatewayService service;

    @BeforeEach
    void setUp() {
        auditLog = new AuditLog();
        ledger = new QuotaLedger();
        registry = new PolicyRegistry(auditLog);
        properties = new GatewayProperties();
        service = new QuotaGatewayService(registry, ledger, auditLog, properties);
    }

    private void print(String requestId, Decision d) {
        System.out.printf("[判定] requestId=%s tenant=%s allowed=%s reason=%s policyVersion=%d basis=%s%n",
                requestId, d.tenantId(), d.allowed(), d.reason(), d.policyVersion(), d.detail());
    }

    @Test
    @DisplayName("租户隔离：tenant-a 突发打满自身限流，不影响 tenant-b 已被允许的请求")
    void tenantIsolation() {
        service.refreshPolicies(List.of(
                new TenantPolicy("tenant-a", 0.0001d, 2L, 100L, 60_000L),
                new TenantPolicy("tenant-b", 0.0001d, 2L, 100L, 60_000L)));

        // tenant-a 突发 5 个请求，只有 2 个能拿到令牌
        int aAllowed = 0;
        for (int i = 1; i <= 5; i++) {
            Decision d = service.decide("tenant-a", "a-" + i);
            print("a-" + i, d);
            if (d.allowed()) {
                aAllowed++;
            }
        }
        assertEquals(2, aAllowed, "tenant-a 突发容量为 2，超出部分应被 RATE_LIMIT_EXCEEDED 拒绝");

        // tenant-b 的令牌桶未被 tenant-a 消耗
        for (int i = 1; i <= 2; i++) {
            Decision d = service.decide("tenant-b", "b-" + i);
            print("b-" + i, d);
            assertTrue(d.allowed(), "tenant-b 不应受 tenant-a 突发影响");
        }
        Decision b3 = service.decide("tenant-b", "b-3");
        print("b-3", b3);
        assertFalse(b3.allowed());
        assertEquals(RejectionReason.RATE_LIMIT_EXCEEDED, b3.reason());
    }

    @Test
    @DisplayName("临界额度：配额恰好用满，第 limit+1 个请求以 QUOTA_EXCEEDED 拒绝")
    void quotaBoundary() {
        service.refreshPolicies(List.of(
                new TenantPolicy("tenant-q", 1_000d, 1_000L, 3L, 60_000L)));

        for (int i = 1; i <= 3; i++) {
            Decision d = service.decide("tenant-q", "q-" + i);
            print("q-" + i, d);
            assertTrue(d.allowed(), "第 " + i + " 个请求应在配额内被允许");
        }
        assertEquals(3L, ledger.currentUsage("tenant-q"));

        Decision d4 = service.decide("tenant-q", "q-4");
        print("q-4", d4);
        assertFalse(d4.allowed());
        assertEquals(RejectionReason.QUOTA_EXCEEDED, d4.reason());
        assertEquals(3L, ledger.currentUsage("tenant-q"), "被拒绝的请求不得扣减配额");
    }

    @Test
    @DisplayName("并发突发：50 个并发请求竞争容量为 10 的令牌桶，恰好放行 10 个，不超发")
    void concurrentBurst() throws InterruptedException {
        service.refreshPolicies(List.of(
                new TenantPolicy("tenant-c", 0.0001d, 10L, 1_000L, 60_000L)));

        int total = 50;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Decision> results = new ConcurrentLinkedQueue<>();
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            String requestId = "burst-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Decision d = service.decide("tenant-c", requestId);
                results.add(d);
                if (d.allowed()) {
                    allowed.incrementAndGet();
                } else if (d.reason() == RejectionReason.RATE_LIMIT_EXCEEDED) {
                    rateLimited.incrementAndGet();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        results.forEach(d -> print(d.requestId(), d));
        assertEquals(total, results.size());
        assertEquals(10, allowed.get(), "令牌桶容量 10，并发下恰好放行 10 个");
        assertEquals(40, rateLimited.get(), "其余 40 个必须因 RATE_LIMIT_EXCEEDED 被拒绝");
        assertEquals(10L, ledger.currentUsage("tenant-c"), "配额计量必须与实际放行数一致，不超发不少计");
    }

    @Test
    @DisplayName("重复提交/重试：同一 requestId 多次提交结论一致且用量只计一次")
    void duplicateSubmission() {
        service.refreshPolicies(List.of(
                new TenantPolicy("tenant-d", 1_000d, 1_000L, 2L, 60_000L)));

        List<Decision> retries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Decision d = service.decide("tenant-d", "dup-1");
            retries.add(d);
            print("dup-1", d);
        }
        assertTrue(retries.stream().allMatch(Decision::allowed), "重试应重放首次 ALLOWED 结论");
        assertEquals(1L, ledger.currentUsage("tenant-d"), "同一业务请求重试不得重复扣减用量");

        // 不同 requestId 正常计费，直至配额满
        Decision d2 = service.decide("tenant-d", "dup-2");
        print("dup-2", d2);
        assertTrue(d2.allowed());
        Decision d3 = service.decide("tenant-d", "dup-3");
        print("dup-3", d3);
        assertFalse(d3.allowed());
        assertEquals(RejectionReason.QUOTA_EXCEEDED, d3.reason());

        // 被拒绝的请求重试，重放一致的拒绝结论
        Decision d3retry = service.decide("tenant-d", "dup-3");
        print("dup-3(retry)", d3retry);
        assertFalse(d3retry.allowed());
        assertEquals(RejectionReason.QUOTA_EXCEEDED, d3retry.reason());
        assertEquals(2L, ledger.currentUsage("tenant-d"));
    }

    @Test
    @DisplayName("并发重复提交：同一 requestId 并发到达，仅放行一次且计量一次")
    void concurrentDuplicateSubmission() throws InterruptedException {
        service.refreshPolicies(List.of(
                new TenantPolicy("tenant-e", 1_000d, 1_000L, 10L, 60_000L)));

        int copies = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Decision> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < copies; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results.add(service.decide("tenant-e", "same-request"));
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        results.forEach(d -> print(d.requestId(), d));
        assertEquals(copies, results.size());
        assertTrue(results.stream().allMatch(Decision::allowed), "并发重复提交应全部得到一致的 ALLOWED 结论");
        assertEquals(1L, ledger.currentUsage("tenant-e"), "并发重复提交只应计量一次");
    }

    @Test
    @DisplayName("配置刷新：原子切换策略，版本递增，已计用量不丢失，新策略立即生效")
    void configRefresh() {
        long v1 = service.refreshPolicies(List.of(
                new TenantPolicy("tenant-f", 1_000d, 1_000L, 2L, 60_000L)));

        Decision d1 = service.decide("tenant-f", "f-1");
        Decision d2 = service.decide("tenant-f", "f-2");
        print("f-1", d1);
        print("f-2", d2);
        assertTrue(d1.allowed() && d2.allowed());
        assertEquals(2L, ledger.currentUsage("tenant-f"));

        // 刷新：配额 2 -> 5，速率也变更
        long v2 = service.refreshPolicies(List.of(
                new TenantPolicy("tenant-f", 500d, 500L, 5L, 60_000L)));
        assertTrue(v2 > v1, "刷新后策略版本必须单调递增");

        // 已计 2 用量保留：新配额 5 下还能再放行 3 个
        for (int i = 3; i <= 5; i++) {
            Decision d = service.decide("tenant-f", "f-" + i);
            print("f-" + i, d);
            assertTrue(d.allowed(), "刷新后新配额应立即可用，且历史用量未丢失");
        }
        Decision d6 = service.decide("tenant-f", "f-6");
        print("f-6", d6);
        assertFalse(d6.allowed());
        assertEquals(RejectionReason.QUOTA_EXCEEDED, d6.reason());
        assertEquals(5L, ledger.currentUsage("tenant-f"));

        // 策略变更必须留下审计
        boolean hasPolicyAudit = auditLog.entries().stream()
                .anyMatch(e -> e.requestId() == null && e.policyVersion() == v2
                        && e.basis().contains("策略刷新"));
        assertTrue(hasPolicyAudit, "策略刷新必须产生审计记录");
    }

    @Test
    @DisplayName("降级：配置不可用时 FAIL_CLOSED 明确拒绝，FAIL_OPEN 按语义放行，原因可区分")
    void degradation() {
        service.refreshPolicies(List.of(
                new TenantPolicy("tenant-g", 1_000d, 1_000L, 10L, 60_000L)));

        registry.setAvailable(false);

        // FAIL_CLOSED（默认）
        Decision closed = service.decide("tenant-g", "g-1");
        print("g-1", closed);
        assertFalse(closed.allowed());
        assertEquals(RejectionReason.POLICY_UNAVAILABLE_REJECTED, closed.reason());

        // 切换为 FAIL_OPEN
        properties.setDegradationMode("FAIL_OPEN");
        Decision open = service.decide("tenant-g", "g-2");
        print("g-2", open);
        assertTrue(open.allowed());
        assertEquals(RejectionReason.POLICY_UNAVAILABLE_ALLOWED, open.reason(),
                "降级放行必须携带可区分的降级原因");
        assertEquals(0L, ledger.currentUsage("tenant-g"), "降级放行不得扣减配额");

        // 恢复可用后正常判定
        registry.setAvailable(true);
        Decision normal = service.decide("tenant-g", "g-3");
        print("g-3", normal);
        assertTrue(normal.allowed());
        assertEquals(RejectionReason.NONE, normal.reason());
        assertEquals(1L, ledger.currentUsage("tenant-g"));
    }

    @Test
    @DisplayName("租户识别与未配置租户：缺失标识与未知租户分别给出可区分原因")
    void tenantIdentification() {
        service.refreshPolicies(List.of(
                new TenantPolicy("tenant-h", 1_000d, 1_000L, 10L, 60_000L)));

        Decision missing = service.decide(null, "h-1");
        print("h-1", missing);
        assertFalse(missing.allowed());
        assertEquals(RejectionReason.TENANT_NOT_IDENTIFIED, missing.reason());

        Decision unknown = service.decide("tenant-ghost", "h-2");
        print("h-2", unknown);
        assertFalse(unknown.allowed());
        assertEquals(RejectionReason.TENANT_NOT_CONFIGURED, unknown.reason());
    }

    @Test
    @DisplayName("审计：每次拒绝与策略变更都包含判定依据")
    void auditTrail() {
        service.refreshPolicies(List.of(
                new TenantPolicy("tenant-i", 0.0001d, 1L, 1L, 60_000L)));

        Decision ok = service.decide("tenant-i", "i-1");
        Decision rejected = service.decide("tenant-i", "i-2");
        print("i-1", ok);
        print("i-2", rejected);
        assertFalse(rejected.allowed());

        List<AuditEntry> entries = auditLog.entries();
        entries.forEach(e -> System.out.printf("[审计] requestId=%s tenant=%s allowed=%s reason=%s basis=%s%n",
                e.requestId(), e.tenantId(), e.allowed(), e.reason(), e.basis()));

        assertTrue(entries.stream().anyMatch(e -> e.requestId() == null && e.basis().contains("策略刷新")),
                "策略变更必须有审计");
        assertTrue(entries.stream().anyMatch(e -> "i-2".equals(e.requestId())
                        && Boolean.FALSE.equals(e.allowed())
                        && e.basis() != null && !e.basis().isBlank()),
                "每次拒绝必须有带判定依据的审计");
    }
}
