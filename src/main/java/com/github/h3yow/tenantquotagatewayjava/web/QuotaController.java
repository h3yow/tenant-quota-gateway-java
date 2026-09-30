package com.github.h3yow.tenantquotagatewayjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tenantquotagatewayjava.config.GatewayProperties;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.Reservation;
import com.github.h3yow.tenantquotagatewayjava.limit.Settlement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 预占 / 结算 HTTP 接口。路径在 /quota/** 下，不经过仅注册于 /api/** 的限流过滤器，
 * 因此一次异步业务不会被"预占 + 业务请求"双重计量；预占本身的速率/配额判定由
 * {@link QuotaLimitEngine#reserve} 完成。
 *
 * <p>请求头：租户头、请求标识头（名称可配置）。JSON body：
 * {@code reservationId}（结算/预占幂等键）、{@code estimatedAmount}/{@code actualAmount}。</p>
 */
@RestController
@RequestMapping("/quota")
public class QuotaController {

    private static final Logger log = LoggerFactory.getLogger(QuotaController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuotaLimitEngine engine;
    private final GatewayProperties properties;

    public QuotaController(QuotaLimitEngine engine, GatewayProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    /** 申请预占：POST /quota/reservations。 */
    @PostMapping("/reservations")
    public void reserve(@RequestBody(required = false) Map<String, Object> body,
                        HttpServletRequest httpRequest, HttpServletResponse response)
            throws java.io.IOException {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String requestId = resolveRequestId(httpRequest);
        String tenantId = httpRequest.getHeader(properties.getTenantHeader());
        String reservationId = asString(payload.get("reservationId"));
        long estimated = asLong(payload.get("estimatedAmount"));

        Reservation decision = engine.reserve(requestId, tenantId, reservationId, estimated);
        log.info("预占判定 requestId={} tenant={} reservationId={} estimated={} reason={} basis={}",
                requestId, tenantId, reservationId, estimated, decision.reason(),
                decision.detail());

        response.setStatus(decision.reason().getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
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
            response.setHeader("Retry-After",
                    String.valueOf((decision.retryAfterMillis() + 999L) / 1000L));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", decision.allowed());
        out.put("requestId", decision.requestId());
        out.put("tenantId", decision.tenantId());
        out.put("reservationId", decision.reservationId());
        out.put("reason", decision.reason().name());
        out.put("estimatedAmount", decision.estimatedAmount());
        out.put("heldTotal", decision.heldTotal());
        out.put("expiresAtEpochMilli", decision.expiresAtEpochMilli());
        out.put("policyVersion", decision.policyVersion());
        out.put("rateRemaining", decision.rateRemaining());
        out.put("quotaRemaining", decision.quotaRemaining());
        out.put("retryAfterMillis", decision.retryAfterMillis());
        out.put("basis", decision.detail());
        MAPPER.writeValue(response.getOutputStream(), out);
    }

    /** 按实际用量结算：POST /quota/settlements。 */
    @PostMapping("/settlements")
    public void settle(@RequestBody(required = false) Map<String, Object> body,
                       HttpServletRequest httpRequest, HttpServletResponse response)
            throws java.io.IOException {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String requestId = resolveRequestId(httpRequest);
        String tenantId = httpRequest.getHeader(properties.getTenantHeader());
        String reservationId = asString(payload.get("reservationId"));
        long actual = asLong(payload.get("actualAmount"));

        Settlement result = engine.settleReservation(requestId, tenantId, reservationId, actual);
        log.info("结算判定 requestId={} tenant={} reservationId={} actual={} status={} basis={}",
                requestId, tenantId, reservationId, actual, result.status(), result.detail());

        response.setStatus(result.status().getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.setHeader("X-Request-Id", String.valueOf(result.requestId()));
        if (result.tenantId() != null) {
            response.setHeader("X-Tenant-Id", result.tenantId());
        }
        response.setHeader("X-Settlement-Status", result.status().name());
        response.setHeader("X-Settlement-Effective", String.valueOf(result.effective()));
        if (result.policyVersion() != null) {
            response.setHeader("X-Policy-Version", String.valueOf(result.policyVersion()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("effective", result.effective());
        out.put("requestId", result.requestId());
        out.put("tenantId", result.tenantId());
        out.put("reservationId", result.reservationId());
        out.put("status", result.status().name());
        out.put("reservedAmount", result.reservedAmount());
        out.put("actualAmount", result.actualAmount());
        out.put("delta", result.delta());
        out.put("settledUsed", result.settledUsed());
        out.put("policyVersion", result.policyVersion());
        out.put("quotaRemaining", result.quotaRemaining());
        out.put("quotaLimit", result.quotaLimit());
        out.put("basis", result.detail());
        MAPPER.writeValue(response.getOutputStream(), out);
    }

    private String resolveRequestId(HttpServletRequest httpRequest) {
        String header = httpRequest.getHeader(properties.getRequestIdHeader());
        return (header != null && !header.isBlank())
                ? header.trim()
                : UUID.randomUUID().toString();
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** 缺失/非数字按 -1 处理（&lt;1 / &lt;0 校验在引擎层完成并给出 INVALID 原因）。 */
    private long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
    }
}
