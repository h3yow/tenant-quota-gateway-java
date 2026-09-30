package com.github.h3yow.tenantquotagatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预占/结算两阶段端点的 HTTP 端到端测试。基于默认 policies.json：
 * tenant-a 突发10/配额5；通过独立租户头隔离，避免与 {@link WebEndToEndTest} 的用量互相干扰
 * （该用例未预占 tenant-a 之外的独立键；这里使用 tenant-a 但用独立 requestId 观察响应结构）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reserveAndSettleHttpLifecycle() throws Exception {
        // tenant-a 配额上限 5。为不受其它用例残留用量影响，这里只校验响应结构与相对变化，
        // 并使用幂等键保证可重复提交语义。
        String reserveBody = "{\"estimated\":3}";

        // 1. 预占成功
        MvcResult reserved = mockMvc.perform(post("/api/quotas/reservations")
                        .header("X-Tenant-Id", "tenant-http")
                        .header("Idempotency-Key", "http-job-1")
                        .header("X-Request-Id", "http-reserve-1")
                        .contentType(MediaType.APPLICATION_JSON).content(reserveBody))
                .andExpect(status().isOk()).andReturn();
        System.out.println("[HTTP预占] " + reserved.getResponse().getStatus()
                + " reason=" + reserved.getResponse().getHeader("X-Decision-Reason")
                + " status=" + reserved.getResponse().getHeader("X-Reservation-Status")
                + " body=" + reserved.getResponse().getContentAsString());
        assertEquals("RESERVED", reserved.getResponse().getHeader("X-Decision-Reason"));
        assertEquals("ACTIVE", reserved.getResponse().getHeader("X-Reservation-Status"));
        assertTrue(reserved.getResponse().getContentAsString().contains("\"basis\""));

        // 2. 重复预占 -> 幂等重放，不二次占用
        MvcResult replay = mockMvc.perform(post("/api/quotas/reservations")
                        .header("X-Tenant-Id", "tenant-http")
                        .header("Idempotency-Key", "http-job-1")
                        .header("X-Request-Id", "http-reserve-2")
                        .contentType(MediaType.APPLICATION_JSON).content(reserveBody))
                .andExpect(status().isOk()).andReturn();
        System.out.println("[HTTP预占重放] reason="
                + replay.getResponse().getHeader("X-Decision-Reason"));
        assertEquals("RESERVATION_REPLAY", replay.getResponse().getHeader("X-Decision-Reason"));

        // 3. 结算真实用量 1 -> 多退少补
        MvcResult settled = mockMvc.perform(post("/api/quotas/settlements")
                        .header("X-Tenant-Id", "tenant-http")
                        .header("Idempotency-Key", "http-job-1")
                        .header("X-Request-Id", "http-settle-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actual\":1}"))
                .andExpect(status().isOk()).andReturn();
        System.out.println("[HTTP结算] " + settled.getResponse().getStatus()
                + " reason=" + settled.getResponse().getHeader("X-Decision-Reason")
                + " body=" + settled.getResponse().getContentAsString());
        assertEquals("SETTLED", settled.getResponse().getHeader("X-Decision-Reason"));
        assertEquals("SETTLED", settled.getResponse().getHeader("X-Reservation-Status"));
        assertTrue(settled.getResponse().getContentAsString().contains("多占退回 2"));

        // 4. 重复结算 -> 幂等重放
        MvcResult settleReplay = mockMvc.perform(post("/api/quotas/settlements")
                        .header("X-Tenant-Id", "tenant-http")
                        .header("Idempotency-Key", "http-job-1")
                        .header("X-Request-Id", "http-settle-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actual\":1}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("RESERVATION_REPLAY",
                settleReplay.getResponse().getHeader("X-Decision-Reason"));

        // 5. 结算不存在的键 -> 404 RESERVATION_NOT_FOUND
        MvcResult notFound = mockMvc.perform(post("/api/quotas/settlements")
                        .header("X-Tenant-Id", "tenant-http")
                        .header("Idempotency-Key", "no-such-job")
                        .header("X-Request-Id", "http-settle-ghost")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actual\":1}"))
                .andExpect(status().isNotFound()).andReturn();
        System.out.println("[HTTP结算未找到] " + notFound.getResponse().getStatus()
                + " reason=" + notFound.getResponse().getHeader("X-Decision-Reason"));
        assertEquals("RESERVATION_NOT_FOUND",
                notFound.getResponse().getHeader("X-Decision-Reason"));

        // 6. 参数非法 -> 400 INVALID_ARGUMENT
        MvcResult invalid = mockMvc.perform(post("/api/quotas/reservations")
                        .header("X-Tenant-Id", "tenant-http")
                        .header("Idempotency-Key", "http-job-bad")
                        .header("X-Request-Id", "http-reserve-bad")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estimated\":0}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertEquals("INVALID_ARGUMENT", invalid.getResponse().getHeader("X-Decision-Reason"));

        // 7. 普通 /api/echo 仍是原有一次性判定（reason=ALLOWED 即走一次性计量）。
        //    使用本用例专用的 tenant-http，避免消耗 tenant-b 紧张的突发令牌而干扰其它用例。
        MvcResult echo = mockMvc.perform(get("/api/echo").header("X-Tenant-Id", "tenant-http")
                        .header("X-Request-Id", "http-echo-plain"))
                .andExpect(status().isOk()).andReturn();
        System.out.println("[HTTP普通请求不受影响] /api/echo reason="
                + echo.getResponse().getHeader("X-Decision-Reason"));
        assertEquals("ALLOWED", echo.getResponse().getHeader("X-Decision-Reason"));
    }
}
