package com.github.h3yow.tenantquotagatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预占 / 结算 HTTP 接口端到端测试（默认 policies.json，tenant-a 上限 5）。
 * 输出中带 requestId 与判定依据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuotaHttpEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reserveThenSettleOverHttpWithDistinguishableStatus() throws Exception {
        // 1. 预占 3：200
        MvcResult reserve = mockMvc.perform(post("/quota/reservations")
                        .header("X-Tenant-Id", "tenant-async")
                        .header("X-Request-Id", "q-http-1")
                        .contentType("application/json")
                        .content("""
                                {"reservationId":"http-job-1","estimatedAmount":18}
                                """))
                .andExpect(status().isOk()).andReturn();
        System.out.println("[HTTP预占] " + reserve.getResponse().getStatus()
                + " reason=" + reserve.getResponse().getHeader("X-Decision-Reason")
                + " remain=" + reserve.getResponse().getHeader("X-Quota-Remaining")
                + " body=" + reserve.getResponse().getContentAsString());
        assertEquals("ALLOWED", reserve.getResponse().getHeader("X-Decision-Reason"));
        assertEquals("2", reserve.getResponse().getHeader("X-Quota-Remaining"),
                "上限20占用18后可再放行=2");

        // 2. 预占期间普通额度只剩 2；再预占 3 被 429
        MvcResult over = mockMvc.perform(post("/quota/reservations")
                        .header("X-Tenant-Id", "tenant-async")
                        .header("X-Request-Id", "q-http-2")
                        .contentType("application/json")
                        .content("""
                                {"reservationId":"http-job-2","estimatedAmount":3}
                                """))
                .andExpect(status().isTooManyRequests()).andReturn();
        System.out.println("[HTTP预占-超额] reason="
                + over.getResponse().getHeader("X-Decision-Reason")
                + " body=" + over.getResponse().getContentAsString());
        assertEquals("QUOTA_EXHAUSTED", over.getResponse().getHeader("X-Decision-Reason"));

        // 3. 结算实际 1：退回 2
        MvcResult settle = mockMvc.perform(post("/quota/settlements")
                        .header("X-Tenant-Id", "tenant-async")
                        .header("X-Request-Id", "q-http-s1")
                        .contentType("application/json")
                        .content("""
                                {"reservationId":"http-job-1","actualAmount":10}
                                """))
                .andExpect(status().isOk()).andReturn();
        System.out.println("[HTTP结算] status="
                + settle.getResponse().getHeader("X-Settlement-Status")
                + " effective=" + settle.getResponse().getHeader("X-Settlement-Effective")
                + " body=" + settle.getResponse().getContentAsString());
        assertEquals("SETTLED_REFUNDED", settle.getResponse().getHeader("X-Settlement-Status"));
        assertEquals("true", settle.getResponse().getHeader("X-Settlement-Effective"));

        // 4. 重复结算：200 但状态为 DUPLICATE、effective=false
        MvcResult dup = mockMvc.perform(post("/quota/settlements")
                        .header("X-Tenant-Id", "tenant-async")
                        .header("X-Request-Id", "q-http-s2")
                        .contentType("application/json")
                        .content("""
                                {"reservationId":"http-job-1","actualAmount":9}
                                """))
                .andExpect(status().isOk()).andReturn();
        System.out.println("[HTTP结算-重复] status="
                + dup.getResponse().getHeader("X-Settlement-Status")
                + " body=" + dup.getResponse().getContentAsString());
        assertEquals("SETTLEMENT_DUPLICATE", dup.getResponse().getHeader("X-Settlement-Status"));
        assertEquals("false", dup.getResponse().getHeader("X-Settlement-Effective"));

        // 5. 非法预占（缺 reservationId）：400
        MvcResult invalid = mockMvc.perform(post("/quota/reservations")
                        .header("X-Tenant-Id", "tenant-async")
                        .header("X-Request-Id", "q-http-3")
                        .contentType("application/json")
                        .content("""
                                {"estimatedAmount":1}
                                """))
                .andExpect(status().isBadRequest()).andReturn();
        assertEquals("INVALID_REQUEST", invalid.getResponse().getHeader("X-Decision-Reason"));

        // 6. 不存在的预占结算：404 SETTLEMENT_NOT_FOUND
        MvcResult notFound = mockMvc.perform(post("/quota/settlements")
                        .header("X-Tenant-Id", "tenant-async")
                        .header("X-Request-Id", "q-http-s4")
                        .contentType("application/json")
                        .content("""
                                {"reservationId":"missing-job","actualAmount":1}
                                """))
                .andExpect(status().isNotFound()).andReturn();
        System.out.println("[HTTP结算-缺失] status="
                + notFound.getResponse().getHeader("X-Settlement-Status"));
        assertEquals("SETTLEMENT_NOT_FOUND",
                notFound.getResponse().getHeader("X-Settlement-Status"));
        assertTrue(notFound.getResponse().getContentAsString().contains("\"basis\""));
    }
}
