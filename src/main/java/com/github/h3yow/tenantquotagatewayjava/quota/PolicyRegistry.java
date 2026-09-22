package com.github.h3yow.tenantquotagatewayjava.quota;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 租户策略注册表：持有不可变策略快照，刷新以原子引用替换完成，版本号单调递增。
 * 任何判定都基于同一个快照，刷新进行中也不会出现半新半旧的策略。
 * available=false 表示配置源不可用，触发既定降级语义。
 * 每次刷新与可用性切换都写审计。
 */
public class PolicyRegistry {

    private record Snapshot(long version, Map<String, TenantPolicy> policies) {
    }

    private final AuditLog auditLog;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(0, Map.of()));
    private final AtomicLong versionSeq = new AtomicLong(0);
    private volatile boolean available = true;

    public PolicyRegistry(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * 原子替换全部策略并递增版本。
     *
     * @return 新版本号
     */
    public long replaceAll(List<TenantPolicy> policies) {
        Map<String, TenantPolicy> map = new LinkedHashMap<>();
        for (TenantPolicy policy : policies) {
            map.put(policy.tenantId(), policy);
        }
        long newVersion = versionSeq.incrementAndGet();
        snapshot.set(new Snapshot(newVersion, Collections.unmodifiableMap(map)));
        auditLog.record(new AuditEntry(Instant.now(), null, "*", null, RejectionReason.NONE, newVersion,
                "策略刷新: tenantCount=" + map.size() + ", tenants=" + map.keySet()));
        return newVersion;
    }

    public TenantPolicy find(String tenantId) {
        return snapshot.get().policies().get(tenantId);
    }

    public Map<String, TenantPolicy> snapshot() {
        return snapshot.get().policies();
    }

    public long version() {
        return snapshot.get().version();
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        boolean before = this.available;
        this.available = available;
        if (before != available) {
            auditLog.record(new AuditEntry(Instant.now(), null, "*", null, RejectionReason.NONE, version(),
                    "配置可用性变更: " + before + " -> " + available));
        }
    }
}
