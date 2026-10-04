package com.github.h3yow.tenantquotagatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关可配置项（前缀 tenant-quota）。
 */
@ConfigurationProperties(prefix = "tenant-quota")
public class GatewayProperties {

    /** 携带租户标识的 HTTP 请求头名称。 */
    private String tenantHeader = "X-Tenant-Id";

    /** 携带幂等键的 HTTP 请求头名称（为空表示不从请求头读取）。 */
    private String idempotencyHeader = "Idempotency-Key";

    /** 携带请求标识的 HTTP 请求头名称；缺失时由网关生成。 */
    private String requestIdHeader = "X-Request-Id";

    /** 策略 JSON 文件位置（classpath: 或 file: 前缀）。 */
    private String policyLocation = "classpath:policies.json";

    /**
     * 降级策略：配置/内部状态不可用时 true=放行(fail-open)，false=拒绝(fail-closed)。
     */
    private boolean failOpen = false;

    /** 幂等键记录保留时长（毫秒）。 */
    private long idempotencyTtlMillis = 24 * 60 * 60 * 1000L;

    /** 预占占用的超时时长（毫秒）：到期未结算自动释放占用。 */
    private long reservationTtlMillis = 5 * 60 * 1000L;

    /**
     * 预占超时自动释放的巡检节奏（毫秒）：后台按此间隔扫描所有租户，到点占用无需任何后续请求
     * 即被自动释放；&lt;=0 表示关闭后台巡检，退回仅靠下次请求顺带清理的惰性模式。
     */
    private long reservationReaperIntervalMillis = 1000L;

    /** 内存审计记录保留条数。 */
    private int auditBufferSize = 1000;

    /** 是否允许通过 POST /admin/policies/reload 手动刷新策略。 */
    private boolean adminEndpointsEnabled = true;

    public String getTenantHeader() { return tenantHeader; }
    public void setTenantHeader(String tenantHeader) { this.tenantHeader = tenantHeader; }

    public String getIdempotencyHeader() { return idempotencyHeader; }
    public void setIdempotencyHeader(String idempotencyHeader) {
        this.idempotencyHeader = idempotencyHeader;
    }

    public String getRequestIdHeader() { return requestIdHeader; }
    public void setRequestIdHeader(String requestIdHeader) {
        this.requestIdHeader = requestIdHeader;
    }

    public String getPolicyLocation() { return policyLocation; }
    public void setPolicyLocation(String policyLocation) { this.policyLocation = policyLocation; }

    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }

    public long getIdempotencyTtlMillis() { return idempotencyTtlMillis; }
    public void setIdempotencyTtlMillis(long idempotencyTtlMillis) {
        this.idempotencyTtlMillis = idempotencyTtlMillis;
    }

    public long getReservationTtlMillis() { return reservationTtlMillis; }
    public void setReservationTtlMillis(long reservationTtlMillis) {
        this.reservationTtlMillis = reservationTtlMillis;
    }

    public long getReservationReaperIntervalMillis() {
        return reservationReaperIntervalMillis;
    }
    public void setReservationReaperIntervalMillis(long reservationReaperIntervalMillis) {
        this.reservationReaperIntervalMillis = reservationReaperIntervalMillis;
    }

    public int getAuditBufferSize() { return auditBufferSize; }
    public void setAuditBufferSize(int auditBufferSize) { this.auditBufferSize = auditBufferSize; }

    public boolean isAdminEndpointsEnabled() { return adminEndpointsEnabled; }
    public void setAdminEndpointsEnabled(boolean adminEndpointsEnabled) {
        this.adminEndpointsEnabled = adminEndpointsEnabled;
    }
}
