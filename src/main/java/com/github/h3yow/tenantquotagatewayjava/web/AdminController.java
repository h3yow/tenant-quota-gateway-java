package com.github.h3yow.tenantquotagatewayjava.web;

import com.github.h3yow.tenantquotagatewayjava.config.GatewayProperties;
import com.github.h3yow.tenantquotagatewayjava.limit.AuditEvent;
import com.github.h3yow.tenantquotagatewayjava.limit.DefaultAuditLogger;
import com.github.h3yow.tenantquotagatewayjava.limit.PolicySnapshot;
import com.github.h3yow.tenantquotagatewayjava.limit.QuotaLimitEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 管理端接口：策略刷新、租户计量状态、审计记录查看。不参与限流判定。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final QuotaLimitEngine engine;
    private final DefaultAuditLogger auditLogger;
    private final GatewayProperties properties;

    public AdminController(QuotaLimitEngine engine, DefaultAuditLogger auditLogger,
                           GatewayProperties properties) {
        this.engine = engine;
        this.auditLogger = auditLogger;
        this.properties = properties;
    }

    /** 手动重新加载策略文件。 */
    @PostMapping("/policies/reload")
    public ResponseEntity<?> reload() {
        if (!properties.isAdminEndpointsEnabled()) {
            return ResponseEntity.status(404).build();
        }
        PolicySnapshot snapshot = engine.reloadPolicies();
        return ResponseEntity.ok(Map.of(
                "version", snapshot.version(),
                "tenants", snapshot.policies().size()));
    }

    /** 查看某租户当前计量状态。 */
    @GetMapping("/tenants/{tenantId}")
    public ResponseEntity<?> tenant(@PathVariable String tenantId) {
        if (!properties.isAdminEndpointsEnabled()) {
            return ResponseEntity.status(404).build();
        }
        Optional<Map<String, Object>> view = engine.describeTenant(tenantId);
        return view.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "tenant not found: " + tenantId)));
    }

    /** 查看最近审计记录。 */
    @GetMapping("/audit")
    public ResponseEntity<List<AuditEvent>> audit() {
        if (!properties.isAdminEndpointsEnabled()) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(auditLogger.recentEvents());
    }
}
