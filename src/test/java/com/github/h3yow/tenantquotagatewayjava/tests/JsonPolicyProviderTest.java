package com.github.h3yow.tenantquotagatewayjava.tests;

import com.github.h3yow.tenantquotagatewayjava.config.JsonPolicyProvider;
import com.github.h3yow.tenantquotagatewayjava.limit.Clock;
import com.github.h3yow.tenantquotagatewayjava.limit.PolicySnapshot;
import com.github.h3yow.tenantquotagatewayjava.limit.TenantPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略文件加载/校验/刷新的单元测试。
 */
class JsonPolicyProviderTest {

    /** 内存版 ResourceLoader，按字符串提供策略内容，可模拟文件缺失。 */
    private static final class InMemoryResourceLoader implements ResourceLoader {
        private String json;
        private boolean missing;

        InMemoryResourceLoader(String json) {
            this.json = json;
        }

        void setJson(String json) {
            this.json = json;
        }

        void setMissing(boolean missing) {
            this.missing = missing;
        }

        @Override
        public org.springframework.core.io.Resource getResource(String location) {
            return new org.springframework.core.io.AbstractResource() {
                @Override
                public String getDescription() {
                    return "in-memory:" + location;
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    if (missing) {
                        throw new IOException("not found");
                    }
                    return new java.io.ByteArrayInputStream(
                            json.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public boolean exists() {
                    return !missing;
                }
            };
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }

    private JsonPolicyProvider provider(InMemoryResourceLoader loader) {
        return new JsonPolicyProvider(loader, "mem:policies.json", Clock.system());
    }

    private static final String VALID_V1 = """
            {
              "version": 1,
              "tenants": {
                "tenant-a": {
                  "rateCapacity": 3,
                  "rateRefillPerSecond": 1.5,
                  "quotaLimit": 10,
                  "quotaWindowSeconds": 60
                }
              }
            }
            """;

    @Test
    void loadsValidSnapshot() {
        InMemoryResourceLoader loader = new InMemoryResourceLoader(VALID_V1);
        JsonPolicyProvider p = provider(loader);
        PolicySnapshot snapshot = p.currentSnapshot();
        assertEquals(1L, snapshot.version());
        TenantPolicy policy = snapshot.policyOf("tenant-a");
        assertEquals(3L, policy.rateCapacity());
        assertEquals(1.5d, policy.rateRefillPerSecond(), 0.0001);
        assertEquals(10L, policy.quotaLimit());
        // 快照不可变
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.policies().put("x", policy));
    }

    @Test
    void reloadAtomicallyReplacesSnapshot() {
        InMemoryResourceLoader loader = new InMemoryResourceLoader(VALID_V1);
        JsonPolicyProvider p = provider(loader);
        PolicySnapshot first = p.currentSnapshot();

        String v2 = VALID_V1.replace("\"version\": 1", "\"version\": 2")
                .replace("\"quotaLimit\": 10", "\"quotaLimit\": 20");
        loader.setJson(v2);
        PolicySnapshot second = p.reload();
        assertEquals(2L, second.version());
        assertEquals(20L, second.policyOf("tenant-a").quotaLimit());
        // 旧快照对象保持不变（进行中的判定持有的引用不受影响）
        assertEquals(10L, first.policyOf("tenant-a").quotaLimit());
        assertSame(second, p.currentSnapshot());
        System.out.println("[策略加载] 原子替换成功: v1(quota=10) -> v2(quota=20)，旧快照保持不可变");
    }

    @Test
    void invalidConfigIsRejectedAndKeepsPreviousSnapshot() {
        InMemoryResourceLoader loader = new InMemoryResourceLoader(VALID_V1);
        JsonPolicyProvider p = provider(loader);
        PolicySnapshot good = p.currentSnapshot();

        // 非法参数：quotaLimit 为 0
        String invalid = VALID_V1.replace("\"quotaLimit\": 10", "\"quotaLimit\": 0");
        loader.setJson(invalid);
        IllegalStateException ex = assertThrows(IllegalStateException.class, p::reload);
        System.out.println("[策略加载] 非法配置被拒绝: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("策略参数非法"));
        // 非法刷新不得污染既有快照
        assertSame(good, p.currentSnapshot());

        // 版本回退（v0 < 当前 v1）也必须拒绝
        loader.setJson(VALID_V1.replace("\"version\": 1", "\"version\": 0"));
        assertThrows(IllegalStateException.class, p::reload);
        assertSame(good, p.currentSnapshot());

        // 文件缺失：尚无缓存快照的全新 provider 必须抛出；已有缓存的 provider 继续用最后良好快照
        InMemoryResourceLoader missingLoader = new InMemoryResourceLoader(VALID_V1);
        missingLoader.setMissing(true);
        JsonPolicyProvider fresh = provider(missingLoader);
        assertThrows(IllegalStateException.class, fresh::currentSnapshot);
        // 既有 provider 即使底层文件消失，仍稳定返回最后已知良好快照
        assertSame(good, p.currentSnapshot());
    }

    @Test
    void bundledDefaultPoliciesFileIsLoadable() {
        // 验证随工程发布的 classpath:policies.json 可被实际加载
        org.springframework.core.io.DefaultResourceLoader loader =
                new org.springframework.core.io.DefaultResourceLoader();
        JsonPolicyProvider p = new JsonPolicyProvider(loader,
                "classpath:policies.json", Clock.system());
        PolicySnapshot snapshot = p.currentSnapshot();
        assertTrue(snapshot.version() >= 1);
        assertEquals(3, snapshot.policies().size());
        System.out.println("[策略加载] 默认 policies.json 加载成功: 租户数="
                + snapshot.policies().size());
    }
}
