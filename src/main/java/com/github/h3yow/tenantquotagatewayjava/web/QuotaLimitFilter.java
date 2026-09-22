package com.github.h3yow.tenantquotagatewayjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tenantquotagatewayjava.config.GatewayProperties;
import com.github.h3yow.tenantquotagatewayjava.limit.Decision;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 对受保护接口执行租户限流/配额判定的过滤器。被拒绝的请求不会进入下游。
 *
 * <p>请求头：{@code X-Tenant-Id} 租户、{@code Idempotency-Key} 幂等键、
 * {@code X-Request-Id} 请求标识（缺失自动生成）。</p>
 *
 * <p>拒绝响应携带 {@code X-Decision-Reason}（区分缺失租户/未知租户/速率/配额/降级）、
 * 剩余量头与 {@code Retry-After}，body 为含判定依据的 JSON。</p>
 */
public class QuotaLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(QuotaLimitFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuotaLimitEngine engine;
    private final GatewayProperties properties;

    public QuotaLimitFilter(QuotaLimitEngine engine, GatewayProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        String tenantId = request.getHeader(properties.getTenantHeader());
        String idempotencyKey = properties.getIdempotencyHeader() == null
                ? null : request.getHeader(properties.getIdempotencyHeader());

        request.setAttribute("requestId", requestId);
        request.setAttribute("tenantId", tenantId);

        Decision decision = engine.evaluate(requestId, tenantId,
                (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey);

        log.info("请求判定 requestId={} tenant={} key={} reason={} basis={}",
                requestId, tenantId, idempotencyKey, decision.reason(), decision.detail());

        if (!decision.allowed()) {
            writeDeny(response, decision);
            return;
        }
        response.setHeader("X-Decision-Reason", decision.reason().name());
        filterChain.doFilter(request, response);
    }

    private void writeDeny(HttpServletResponse response, Decision decision) throws IOException {
        response.setStatus(decision.reason().getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("X-Request-Id", String.valueOf(decision.requestId()));
        if (decision.tenantId() != null) {
            response.setHeader("X-Tenant-Id", decision.tenantId());
        }
        response.setHeader("X-Decision-Reason", decision.reason().name());
        if (decision.policyVersion() != null) {
            response.setHeader("X-Policy-Version", String.valueOf(decision.policyVersion()));
        }
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.rateRemaining()));
        response.setHeader("X-Quota-Remaining", String.valueOf(decision.quotaRemaining()));
        if (decision.retryAfterMillis() > 0) {
            long seconds = (decision.retryAfterMillis() + 999L) / 1000L;
            response.setHeader("Retry-After", String.valueOf(seconds));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allowed", false);
        body.put("requestId", decision.requestId());
        body.put("tenantId", decision.tenantId());
        body.put("reason", decision.reason().name());
        body.put("policyVersion", decision.policyVersion());
        body.put("rateRemaining", decision.rateRemaining());
        body.put("quotaRemaining", decision.quotaRemaining());
        body.put("retryAfterMillis", decision.retryAfterMillis());
        body.put("basis", decision.detail());
        MAPPER.writeValue(response.getOutputStream(), body);
    }

    private String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(properties.getRequestIdHeader());
        return (header != null && !header.isBlank())
                ? header.trim()
                : UUID.randomUUID().toString();
    }
}
