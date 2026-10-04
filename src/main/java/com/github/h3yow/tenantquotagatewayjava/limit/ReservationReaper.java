package com.github.h3yow.tenantquotagatewayjava.limit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 预占超时自动释放的后台巡检器。
 *
 * <p>背景：仅靠业务请求“顺带”惰性清理，无法覆盖“租户预占后长期不再发任何请求”的情形——
 * 到期占用会一直挂在窗口已用量里，管理端也看不到释放痕迹。本巡检器按固定节奏
 * （{@code reservation-reaper-interval-millis}）主动调用
 * {@link QuotaLimitEngine#runReaperPass()}，到点占用无需任何后续请求即被确定性失效，
 * 额度退回当前窗口，并为每笔释放写一条独立的 {@code RESERVATION_EXPIRED} 审计。</p>
 *
 * <p>一致性：真正的状态迁移仍发生在每个租户自己的监视器锁内（与业务请求同一把锁、同一套
 * 维护流程），所以巡检与并发的预占/结算/普通判定互斥，同一笔占用不会被释放两次，释放后
 * 额度也不可能被重复使用导致超发。巡检线程只负责“到点叫一声”，不持有任何计量状态。</p>
 *
 * <p>采用单线程 + fixed-delay（而非 fixed-rate）：上一轮没跑完不会叠加下一轮；任何一轮
 * 抛异常都被吞掉并记录，绝不让定时任务静默死掉。间隔配置为 &lt;=0 时不启动后台线程，
 * 退化为仅靠请求顺带清理的惰性模式。</p>
 */
public class ReservationReaper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReservationReaper.class);

    private final QuotaLimitEngine engine;
    private final long intervalMillis;

    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> task;
    private volatile boolean running;

    public ReservationReaper(QuotaLimitEngine engine, long intervalMillis) {
        this.engine = engine;
        this.intervalMillis = intervalMillis;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (intervalMillis <= 0L) {
            log.info("预占超时后台巡检已关闭(reservation-reaper-interval-millis<=0)，"
                    + "到期占用仅在该租户下次请求/管理端查看时惰性释放");
            return;
        }
        ScheduledExecutorService es = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reservation-reaper");
            t.setDaemon(true);
            return t;
        });
        // fixed-delay：两轮之间至少间隔 intervalMillis，避免长巡检下任务堆积
        this.task = es.scheduleWithFixedDelay(this::safePass, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        this.executor = es;
        this.running = true;
        log.info("预占超时后台巡检已启动: 节奏={}ms，到期占用到点自动释放、无需后续请求",
                intervalMillis);
    }

    private void safePass() {
        try {
            int released = engine.runReaperPass();
            if (released > 0) {
                log.info("超时巡检本轮自动释放到期预占 {} 笔（详见 AUDIT/RESERVATION_EXPIRED）",
                        released);
            }
        } catch (RuntimeException e) {
            // 单轮失败不能拖垮后续巡检
            log.warn("超时巡检本轮异常（已忽略，不影响后续巡检）: {}", e.toString());
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        ScheduledFuture<?> t = this.task;
        if (t != null) {
            t.cancel(false);
        }
        ScheduledExecutorService es = this.executor;
        if (es != null) {
            es.shutdownNow();
            try {
                if (!es.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("超时巡检线程在 2s 内未停止");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        this.task = null;
        this.executor = null;
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
