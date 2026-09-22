package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.limit.PolicyProvider;
import com.github.h3yow.tenantquotagatewayjava.limit.PolicySnapshot;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用策略提供者：内存快照可控替换，也可被设置为“配置不可用”。
 */
public class TestPolicies implements PolicyProvider {

    private final AtomicReference<PolicySnapshot> ref = new AtomicReference<>();
    private volatile boolean broken = false;

    public TestPolicies(PolicySnapshot initial) {
        this.ref.set(initial);
    }

    @Override
    public PolicySnapshot currentSnapshot() {
        if (broken) {
            throw new IllegalStateException("模拟配置不可用");
        }
        PolicySnapshot snapshot = ref.get();
        if (snapshot == null) {
            throw new IllegalStateException("模拟无可用策略");
        }
        return snapshot;
    }

    @Override
    public PolicySnapshot reload() {
        return currentSnapshot();
    }

    /** 原子替换为新快照（模拟策略刷新）。 */
    public void swap(PolicySnapshot snapshot) {
        ref.set(snapshot);
    }

    /** 模拟底层配置不可用。 */
    public void breakConfig() {
        this.broken = true;
    }

    /** 从配置不可用中恢复。 */
    public void fixConfig(PolicySnapshot snapshot) {
        this.broken = false;
        ref.set(snapshot);
    }

    public static PolicySnapshot snapshot(long version, TenantPolicy... policies) {
        Map<String, TenantPolicy> map = new java.util.LinkedHashMap<>();
        for (TenantPolicy p : policies) {
            map.put(p.tenantId(), p);
        }
        return new PolicySnapshot(version, Map.copyOf(map));
    }
}
