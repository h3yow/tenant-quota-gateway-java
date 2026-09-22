package com.github.h3yow.tenantquotagatewayjava.limit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认审计日志：按事件类型写入 SLF4J（DENY/降级为 WARN，其余为 INFO），
 * 并在内存中保留有界记录用于管理端查看与测试断言。缓冲淘汰是尽力而为的有界控制。
 */
public class DefaultAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private final Clock clock;
    private final int bufferSize;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentLinkedQueue<AuditEvent> buffer = new ConcurrentLinkedQueue<>();

    public DefaultAuditLogger(Clock clock, int bufferSize) {
        this.clock = clock;
        this.bufferSize = Math.max(1, bufferSize);
    }

    @Override
    public void log(AuditEvent event) {
        String line = format(event);
        switch (event.kind()) {
            case DENY, DEGRADATION -> log.warn(line);
            default -> log.info(line);
        }
        buffer.add(event);
        // 有界淘汰（并发下可能短暂超出少量，最终收敛）
        while (buffer.size() > bufferSize) {
            buffer.poll();
        }
    }

    /** 返回内存中最近的审计记录（按产生顺序）的快照。 */
    public List<AuditEvent> recentEvents() {
        return new ArrayList<>(buffer);
    }

    /** 构造一条带有序号与时间戳的审计事件。 */
    public AuditEvent newEvent(AuditEvent.Kind kind, String requestId, String tenantId,
                               DecisionReason reason, Long policyVersion, String basis) {
        return new AuditEvent(sequence.incrementAndGet(), clock.now(), kind, requestId,
                tenantId, reason, policyVersion, basis);
    }

    private String format(AuditEvent e) {
        return "seq=" + e.sequence()
                + " kind=" + e.kind()
                + " requestId=" + e.requestId()
                + " tenant=" + e.tenantId()
                + " reason=" + (e.reason() == null ? "-" : e.reason())
                + " policyVersion=" + e.policyVersion()
                + " basis='" + e.basis() + "'";
    }
}
