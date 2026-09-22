package com.github.h3yow.tenantquotagatewayjava.web;

import com.github.h3yow.tenantquotagatewayjava.quota.Decision;
import com.github.h3yow.tenantquotagatewayjava.quota.QuotaGatewayService;
import com.github.h3yow.tenantquotagatewayjava.quota.RejectionReason;
import com.github.h3yow.tenantquotagatewayjava.quota.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 受保护的演示接口：POST /api/invoke
 * 通过 X-Tenant-Id 识别租户，X-Request-Id 作为幂等键。
 */
@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final QuotaGatewayService service;
    private final TenantResolver resolver;

    public GatewayController(QuotaGatewayService service, TenantResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @PostMapping("/api/invoke")
    public ResponseEntity<Map<String, Object>> invoke(
            @RequestHeader(value = TenantResolver.TENANT_HEADER, required = false) String tenant,
            @RequestHeader(value = TenantResolver.REQUEST_ID_HEADER, required = false) String requestId) {

        String tenantId = resolver.resolve(tenant, requestId);
        Decision decision = service.decide(tenantId, requestId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("tenantId", decision.tenantId());
        body.put("allowed", decision.allowed());
        body.put("reason", decision.reason().name());
        body.put("policyVersion", decision.policyVersion());
        body.put("basis", decision.detail());

        log.info("请求判定 requestId={} tenant={} allowed={} reason={} basis={}",
                requestId, decision.tenantId(), decision.allowed(), decision.reason(), decision.detail());

        HttpStatus status;
        if (decision.allowed()) {
            status = decision.reason() == RejectionReason.POLICY_UNAVAILABLE_ALLOWED
                    ? HttpStatus.OK : HttpStatus.OK;
        } else {
            status = decision.reason() == RejectionReason.RATE_LIMIT_EXCEEDED
                    ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.FORBIDDEN;
        }
        return ResponseEntity.status(status).body(body);
    }
}
