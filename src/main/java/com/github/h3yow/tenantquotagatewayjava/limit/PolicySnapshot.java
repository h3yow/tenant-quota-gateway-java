package com.github.h3yow.tenantquotagatewayjava.limit;

import java.util.Map;

/**
 * 某一时刻生效的不可变策略快照。策略刷新通过整体替换引用来完成，
 * 进行中的判定永远看到一个完整一致的快照，不会出现半刷新状态。
 *
 * @param version 单调递增的策略版本号
 * @param policies 租户标识 -> 租户策略（不可变）
 */
public record PolicySnapshot(long version, Map<String, TenantPolicy> policies) {

    public TenantPolicy policyOf(String tenantId) {
        return policies.get(tenantId);
    }
}
