package com.github.h3yow.tenantquotagatewayjava.limit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预占超时后台扫描器：按固定间隔主动触发
 * {@link QuotaLimitEngine#sweepExpiredReservations()}，使到期未结算的占用
 * <b>不依赖任何后续请求</b>即可自动释放回当前配额窗口，并逐笔留下
 * {@link AuditEvent.Kind#RESERVATION_EXPIRED} 审计。
 *
 * <p>扫描间隔由 {@code tenant-quota.reservation-sweep-interval-millis} 配置
 * （默认 1000ms）；占用是否过期只取决于预占时确定的绝对过期时刻
 * {@code reservedAt + tenant-quota.reservation-ttl-millis}，扫描节奏只影响
 * 释放被发现的早晚，不影响"是否释放、释放多少"的确定性结论。</p>
 */
@Component
public class ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);

    private final QuotaLimitEngine engine;

    public ReservationExpiryScheduler(QuotaLimitEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${tenant-quota.reservation-sweep-interval-millis:1000}",
            initialDelayString = "${tenant-quota.reservation-sweep-interval-millis:1000}")
    public void sweep() {
        try {
            int released = engine.sweepExpiredReservations();
            if (released > 0) {
                log.info("预占超时后台扫描: 本次自动释放 {} 笔到期占用", released);
            }
        } catch (RuntimeException e) {
            // 扫描失败不影响请求路径；下一周期按同样规则重试，结果确定可收敛
            log.warn("预占超时后台扫描本轮失败，将在下一周期重试: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
