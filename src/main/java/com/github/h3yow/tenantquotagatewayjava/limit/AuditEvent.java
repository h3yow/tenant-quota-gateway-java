package com.github.h3yow.tenantquotagatewayjava.limit;

import java.time.Instant;

/**
 * 一条审计记录：每次拒绝、每次幂等重放以及每次策略变更都会产生。
 *
 * @param sequence      进程内单调递增序号
 * @param timestamp     事件时间
 * @param kind          事件类型
 * @param requestId     请求标识（策略变更事件可能为 null）
 * @param tenantId      租户标识
 * @param reason        判定原因
 * @param policyVersion 相关策略版本
 * @param basis         判定依据的结构化描述
 */
public record AuditEvent(
        long sequence,
        Instant timestamp,
        Kind kind,
        String requestId,
        String tenantId,
        DecisionReason reason,
        Long policyVersion,
        String basis
) {

    public enum Kind {
        /** 请求被拒绝。 */
        DENY,
        /** 请求命中幂等键（重复提交/重试未重复计费）。 */
        IDEMPOTENT_REPLAY,
        /** 策略被刷新。 */
        POLICY_CHANGE,
        /** 降级路径被触发。 */
        DEGRADATION
    }
}
