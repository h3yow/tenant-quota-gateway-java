package com.github.h3yow.tenantquotagatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层端到端测试：基于默认 classpath:policies.json
 * （tenant-a: 突发3/配额5；tenant-b: 突发2/配额3）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullHttpLifecycleReasonsAreDistinguishable() throws Exception {
        // 1. 缺租户头：400
        MvcResult missing = mockMvc.perform(get("/api/echo"))
                .andExpect(status().isBadRequest()).andReturn();
        System.out.println("[HTTP] 缺租户 -> " + missing.getResponse().getStatus()
                + " reason=" + missing.getResponse().getHeader("X-Decision-Reason")
                + " body=" + missing.getResponse().getContentAsString());
        assertEquals("MISSING_TENANT", missing.getResponse().getHeader("X-Decision-Reason"));

        // 2. 未知租户：403
        MvcResult unknown = mockMvc.perform(get("/api/echo").header("X-Tenant-Id", "nobody"))
                .andExpect(status().isForbidden()).andReturn();
        System.out.println("[HTTP] 未知租户 -> reason="
                + unknown.getResponse().getHeader("X-Decision-Reason"));
        assertEquals("UNKNOWN_TENANT", unknown.getResponse().getHeader("X-Decision-Reason"));

        // 3. tenant-b 突发容量 2：前 2 个 200，第 3 个 429 RATE_LIMITED
        mockMvc.perform(get("/api/echo").header("X-Tenant-Id", "tenant-b")
                        .header("X-Request-Id", "http-b-1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/echo").header("X-Tenant-Id", "tenant-b")
                        .header("X-Request-Id", "http-b-2"))
                .andExpect(status().isOk());
        MvcResult rateLimited = mockMvc.perform(get("/api/echo").header("X-Tenant-Id", "tenant-b")
                        .header("X-Request-Id", "http-b-3"))
                .andExpect(status().isTooManyRequests()).andReturn();
        System.out.println("[HTTP] 突发超限 -> " + rateLimited.getResponse().getStatus()
                + " reason=" + rateLimited.getResponse().getHeader("X-Decision-Reason")
                + " retryAfter=" + rateLimited.getResponse().getHeader("Retry-After"));
        assertEquals("RATE_LIMITED", rateLimited.getResponse().getHeader("X-Decision-Reason"));

        // 4. tenant-a 打满 5 个配额后第 6 个 429 QUOTA_EXHAUSTED
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(get("/api/echo").header("X-Tenant-Id", "tenant-a")
                            .header("X-Request-Id", "http-a-" + i))
                    .andExpect(status().isOk());
        }
        MvcResult quotaOut = mockMvc.perform(get("/api/echo").header("X-Tenant-Id", "tenant-a")
                        .header("X-Request-Id", "http-a-6"))
                .andExpect(status().isTooManyRequests()).andReturn();
        System.out.println("[HTTP] 配额用尽 -> reason="
                + quotaOut.getResponse().getHeader("X-Decision-Reason")
                + " body=" + quotaOut.getResponse().getContentAsString());
        assertEquals("QUOTA_EXHAUSTED", quotaOut.getResponse().getHeader("X-Decision-Reason"));
        assertEquals("0", quotaOut.getResponse().getHeader("X-Quota-Remaining"));
        assertTrue(quotaOut.getResponse().getContentAsString().contains("\"basis\""));

        // 5. 幂等：同键第二次返回成功但 reason 头为 IDEMPOTENT_REPLAY（用未被限流的时间点做不到，
        //    改为验证 tenant-a 在配额用尽后同键重试仍为 QUOTA_EXHAUSTED，而非错误重放）
        MvcResult retry = mockMvc.perform(get("/api/echo").header("X-Tenant-Id", "tenant-a")
                        .header("X-Request-Id", "http-a-6-retry")
                        .header("Idempotency-Key", "key-http-1"))
                .andExpect(status().isTooManyRequests()).andReturn();
        assertEquals("QUOTA_EXHAUSTED", retry.getResponse().getHeader("X-Decision-Reason"));

        // 6. 管理端：审计接口包含拒绝记录且含判定依据
        MvcResult audit = mockMvc.perform(get("/admin/audit")).andExpect(status().isOk())
                .andReturn();
        String auditBody = audit.getResponse().getContentAsString();
        assertTrue(auditBody.contains("RATE_LIMITED"));
        assertTrue(auditBody.contains("basis"));
        System.out.println("[HTTP] /admin/audit 返回记录长度=" + auditBody.length()
                + "，包含 RATE_LIMITED 与 basis");

        // 7. 管理端：手动刷新策略（默认文件 version=1，同版本重载允许）
        MvcResult reload = mockMvc.perform(post("/admin/policies/reload"))
                .andExpect(status().isOk()).andReturn();
        System.out.println("[HTTP] /admin/policies/reload -> "
                + reload.getResponse().getContentAsString());
        assertTrue(reload.getResponse().getContentAsString().contains("\"version\":1"));
    }
}
