package com.github.h3yow.tenantquotagatewayjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.tenantquotagatewayjava.config.GatewayProperties;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import com.github.h3yow.tenantquotagatewayjava.limit.ReservationResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 异步业务的两阶段配额端点：先预占（{@code POST /api/quotas/reservations}），
 * 业务结束后结算（{@code POST /api/quotas/settlements}）。
 *
 * <p>这两个路径不经 {@link QuotaLimitFilter} 的一次性判定；租户仍由租户头识别，
 * 业务键通过 {@code Idempotency-Key} 头（预占键）传递。</p>
 */
@RestController
@RequestMapping("/api/quotas")
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuotaLimitEngine engine;
    private final GatewayProperties properties;

    public ReservationController(QuotaLimitEngine engine, GatewayProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    /** 预占：body {"estimated": 用量}；成功返回 200 与预占详情。 */
    @PostMapping("/reservations")
    public ResponseEntity<Map<String, Object>> reserve(HttpServletRequest request,
                                                       @RequestBody(required = false)
                                                       JsonNode body) {
        String requestId = resolveRequestId(request);
        String tenantId = request.getHeader(properties.getTenantHeader());
        String key = properties.getIdempotencyHeader() == null
                ? null : request.getHeader(properties.getIdempotencyHeader());
        long estimated = longField(body, "estimated", -1L);
        ReservationResult result = engine.reserve(requestId, tenantId, key, estimated);
        return toResponse(result, request);
    }

    /** 结算：body {"actual": 真实用量}；重复/乱序/迟到结算结果可区分且只生效一次。 */
    @PostMapping("/settlements")
    public ResponseEntity<Map<String, Object>> settle(HttpServletRequest request,
                                                      @RequestBody(required = false)
                                                      JsonNode body) {
        String requestId = resolveRequestId(request);
        String tenantId = request.getHeader(properties.getTenantHeader());
        String key = properties.getIdempotencyHeader() == null
                ? null : request.getHeader(properties.getIdempotencyHeader());
        long actual = longField(body, "actual", -1L);
        ReservationResult result = engine.settle(requestId, tenantId, key, actual);
        return toResponse(result, request);
    }

    private long longField(JsonNode body, String field, long missing) {
        if (body == null || !body.hasNonNull(field)) {
            return missing;
        }
        JsonNode node = body.get(field);
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        try {
            long v = (long) node.asDouble(Double.NaN);
            return v;
        } catch (RuntimeException e) {
            return missing;
        }
    }

    private ResponseEntity<Map<String, Object>> toResponse(ReservationResult r,
                                                           HttpServletRequest request) {
        log.info("两阶段配额 requestId={} uri={} tenant={} key={} reason={} basis={}",
                r.requestId(), request.getRequestURI(), r.tenantId(), r.reservationKey(),
                r.reason(), r.detail());

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("allowed", r.allowed());
        bodyMap.put("requestId", r.requestId());
        bodyMap.put("tenantId", r.tenantId());
        bodyMap.put("reservationKey", r.reservationKey());
        bodyMap.put("reason", r.reason().name());
        bodyMap.put("status", r.status() == null ? null : r.status().name());
        bodyMap.put("policyVersion", r.policyVersion());
        bodyMap.put("rateRemaining", r.rateRemaining());
        bodyMap.put("quotaRemaining", r.quotaRemaining());
        bodyMap.put("quotaLimit", r.quotaLimit());
        bodyMap.put("requestedAmount", r.estimated());
        bodyMap.put("reservedAmount", r.reservedAmount());
        bodyMap.put("settledAmount", r.settledAmount());
        bodyMap.put("retryAfterMillis", r.retryAfterMillis());
        bodyMap.put("basis", r.detail());

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(r.reason().getHttpStatus());
        builder.header("X-Request-Id", String.valueOf(r.requestId()));
        if (r.tenantId() != null) {
            builder.header("X-Tenant-Id", r.tenantId());
        }
        builder.header("X-Decision-Reason", r.reason().name());
        if (r.status() != null) {
            builder.header("X-Reservation-Status", r.status().name());
        }
        if (r.policyVersion() != null) {
            builder.header("X-Policy-Version", String.valueOf(r.policyVersion()));
        }
        builder.header("X-Quota-Remaining", String.valueOf(r.quotaRemaining()));
        if (r.retryAfterMillis() > 0) {
            long seconds = (r.retryAfterMillis() + 999L) / 1000L;
            builder.header("Retry-After", String.valueOf(seconds));
        }
        return builder.body(bodyMap);
    }

    private String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(properties.getRequestIdHeader());
        return (header != null && !header.isBlank())
                ? header.trim()
                : UUID.randomUUID().toString();
    }
}
