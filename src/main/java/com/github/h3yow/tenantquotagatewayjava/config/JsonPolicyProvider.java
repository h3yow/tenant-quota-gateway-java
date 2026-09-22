package com.github.h3yow.tenantquotagatewayjava.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tenantquotagatewayjava.limit.Clock;
import com.github.h3yow.tenantquotagatewayjava.limit.PolicyProvider;
import com.github.h3yow.tenantquotagatewayjava.limit.PolicySnapshot;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 JSON 文件的策略提供者：加载、校验并缓存不可变快照；reload 时整体替换引用。
 *
 * <p>文件格式：</p>
 * <pre>
 * {
 *   "version": 1,
 *   "tenants": {
 *     "tenant-a": { "rateCapacity": 3, "rateRefillPerSecond": 1.0,
 *                   "quotaLimit": 5, "quotaWindowSeconds": 60 }
 *   }
 * }
 * </pre>
 *
 * <p>首次使用时惰性加载；读取或校验失败时抛出 {@link IllegalStateException}，
 * 由引擎按降级策略处理，不会用部分数据替换既有快照。</p>
 */
public class JsonPolicyProvider implements PolicyProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResourceLoader resourceLoader;
    private final String location;
    private final Clock clock;
    private final AtomicReference<PolicySnapshot> snapshot = new AtomicReference<>();

    public JsonPolicyProvider(ResourceLoader resourceLoader, String location, Clock clock) {
        this.resourceLoader = resourceLoader;
        this.location = location;
        this.clock = clock;
    }

    @Override
    public PolicySnapshot currentSnapshot() {
        PolicySnapshot current = snapshot.get();
        if (current != null) {
            return current;
        }
        // 首次访问惰性加载；失败时不设置缓存，后续请求会重试
        return reload();
    }

    @Override
    public PolicySnapshot reload() {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("策略文件不存在: " + location);
        }
        final JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("策略文件无法读取/解析: " + location + " ("
                    + e.getMessage() + ")", e);
        }
        long version = root.path("version").asLong(-1L);
        if (version < 0) {
            throw new IllegalStateException("策略文件缺少合法 version 字段: " + location);
        }
        JsonNode tenantsNode = root.get("tenants");
        if (tenantsNode == null || !tenantsNode.isObject()) {
            throw new IllegalStateException("策略文件缺少 tenants 对象: " + location);
        }

        Map<String, TenantPolicy> policies = new LinkedHashMap<>();
        tenantsNode.fields().forEachRemaining(entry ->
                policies.put(entry.getKey(), parseTenant(entry.getKey(), entry.getValue())));

        PolicySnapshot previous = snapshot.get();
        if (previous != null && version < previous.version()) {
            throw new IllegalStateException(
                    "策略版本回退被拒绝: 新版本=" + version + " 当前版本=" + previous.version());
        }

        PolicySnapshot next = new PolicySnapshot(version, Map.copyOf(policies));
        snapshot.set(next);
        return next;
    }

    private TenantPolicy parseTenant(String tenantId, JsonNode node) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("策略中存在空租户标识");
        }
        long rateCapacity = node.path("rateCapacity").asLong(-1L);
        double refill = node.path("rateRefillPerSecond").asDouble(-1.0d);
        long quotaLimit = node.path("quotaLimit").asLong(-1L);
        long windowSeconds = node.path("quotaWindowSeconds").asLong(-1L);
        if (rateCapacity < 1 || refill < 0.0d || quotaLimit < 1 || windowSeconds < 1) {
            throw new IllegalStateException("租户 " + tenantId + " 的策略参数非法: "
                    + "rateCapacity=" + rateCapacity + ", rateRefillPerSecond=" + refill
                    + ", quotaLimit=" + quotaLimit + ", quotaWindowSeconds=" + windowSeconds);
        }
        return new TenantPolicy(tenantId, rateCapacity, refill, quotaLimit, windowSeconds);
    }

    /** 解析 JSON 文本为租户策略映射（包内可见便于单测）。 */
    Map<String, TenantPolicy> parse(String json) {
        try {
            JsonNode tenantsNode = MAPPER.readTree(json).get("tenants");
            Map<String, TenantPolicy> result = new LinkedHashMap<>();
            if (tenantsNode != null) {
                tenantsNode.fields().forEachRemaining(entry ->
                        result.put(entry.getKey(), parseTenant(entry.getKey(), entry.getValue())));
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            throw new IllegalStateException("策略 JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
