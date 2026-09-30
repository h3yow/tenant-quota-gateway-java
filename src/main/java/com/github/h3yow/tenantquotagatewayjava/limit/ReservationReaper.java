package com.github.h3yow.tenantquotagatewayjava.limit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台超时预占扫描：周期性触发 {@link QuotaLimitEngine#sweepExpiredReservations()}，
 * 使"预占超时自动失效"在没有任何新请求的情况下也按确定的时间规则生效。
 *
 * <p>正确性并不依赖本线程：每次预占/结算/普通判定进入租户锁时都会做同样的惰性超时
 * 释放；后台扫描只是让释放及时发生、审计及时记录。</p>
 */
public class ReservationReaper {

    private static final Logger log = LoggerFactory.getLogger(ReservationReaper.class);

    private final QuotaLimitEngine engine;
    private final long intervalMillis;
    private volatile boolean running;
    private Thread thread;

    public ReservationReaper(QuotaLimitEngine engine, Clock clock, long intervalMillis) {
        this(engine, intervalMillis);
    }

    public ReservationReaper(QuotaLimitEngine engine, long intervalMillis) {
        this.engine = engine;
        this.intervalMillis = intervalMillis;
    }

    @PostConstruct
    public void start() {
        if (intervalMillis <= 0L) {
            log.info("预占超时后台扫描已关闭(interval<=0)，超时释放仍由请求路径惰性保证");
            return;
        }
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "reservation-reaper");
        thread.setDaemon(true);
        thread.start();
        log.info("预占超时后台扫描已启动, intervalMillis={}", intervalMillis);
    }

    private void loop() {
        while (running) {
            try {
                //noinspection BusyWait
                Thread.sleep(intervalMillis);
                if (running) {
                    engine.sweepExpiredReservations();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 扫描异常绝不能杀死守护线程；下一周期继续
                log.warn("预占超时扫描本轮失败，将在下个周期重试: {}", e.getMessage());
            }
        }
    }

    /** 立即执行一轮扫描（管理端/测试可主动触发）。 */
    public int sweepOnce() {
        return engine.sweepExpiredReservations();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
