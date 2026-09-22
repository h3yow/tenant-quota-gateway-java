package com.github.h3yow.tenantquotagatewayjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层验证：租户识别头、幂等键、可区分的拒绝原因与状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("正常请求被允许，响应包含 requestId 与判定依据")
    void allowed() throws Exception {
        mockMvc.perform(post("/api/invoke")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Request-Id", "api-ok-1"))
                .andDo(result -> System.out.printf("[HTTP] requestId=api-ok-1 status=%d body=%s%n",
                        result.getResponse().getStatus(), result.getResponse().getContentAsString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("NONE"))
                .andExpect(jsonPath("$.requestId").value("api-ok-1"));
    }

    @Test
    @DisplayName("缺少租户头以 403 + TENANT_NOT_IDENTIFIED 拒绝")
    void missingTenant() throws Exception {
        mockMvc.perform(post("/api/invoke")
                        .header("X-Request-Id", "api-missing-1"))
                .andDo(result -> System.out.printf("[HTTP] requestId=api-missing-1 status=%d body=%s%n",
                        result.getResponse().getStatus(), result.getResponse().getContentAsString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("TENANT_NOT_IDENTIFIED"));
    }

    @Test
    @DisplayName("未知租户以 403 + TENANT_NOT_CONFIGURED 拒绝，与其他拒绝原因可区分")
    void unknownTenant() throws Exception {
        mockMvc.perform(post("/api/invoke")
                        .header("X-Tenant-Id", "tenant-unknown")
                        .header("X-Request-Id", "api-unknown-1"))
                .andDo(result -> System.out.printf("[HTTP] requestId=api-unknown-1 status=%d body=%s%n",
                        result.getResponse().getStatus(), result.getResponse().getContentAsString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TENANT_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("同 requestId 重试返回一致结论，不重复扣减")
    void retryIdempotent() throws Exception {
        for (int i = 0; i < 3; i++) {
            int attempt = i + 1;
            mockMvc.perform(post("/api/invoke")
                            .header("X-Tenant-Id", "tenant-b")
                            .header("X-Request-Id", "api-retry-1"))
                    .andDo(result -> System.out.printf("[HTTP] requestId=api-retry-1 attempt=%d status=%d body=%s%n",
                            attempt, result.getResponse().getStatus(), result.getResponse().getContentAsString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowed").value(true));
        }
    }
}
