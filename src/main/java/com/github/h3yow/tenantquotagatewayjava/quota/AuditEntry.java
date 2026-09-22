package com.github.h3yow.tenantquotagatewayjava.quota;

import java.time.Instant;

/**
 * 一条审计记录：每次拒绝以及每次策略变更都会生成。
 *
 * @param timestamp     发生时间
 * @param requestId     相关请求标识（策略变更时为 null）
 * @param tenantId      相关租户
 * @param allowed       判定是否放行（策略变更时为 null）
 * @param reason        拒绝/降级原因
 * @param policyVersion 判定或变更所涉及的策略版本
 * @param basis         判定依据（快照数值、变更前后内容等）
 */
public record AuditEntry(Instant timestamp,
                         String requestId,
                         String tenantId,
                         Boolean allowed,
                         RejectionReason reason,
                         long policyVersion,
                         String basis) {
}
