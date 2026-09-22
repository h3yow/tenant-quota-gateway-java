package com.github.h3yow.tenantquotagatewayjava.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 审计记录器：线程安全地保存并打印每次拒绝与策略变更。
 * 判定依据（basis）与请求标识一同落盘，保证结果可解释、可追溯。
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger("gateway.audit");

    /** 保留最近的审计条目，供测试断言与本地排查 */
    private final CopyOnWriteArrayList<AuditEntry> entries = new CopyOnWriteArrayList<>();

    public void record(AuditEntry entry) {
        entries.add(entry);
        if (entry.requestId() != null) {
            log.info("AUDIT requestId={} tenant={} allowed={} reason={} policyVersion={} basis={}",
                    entry.requestId(), entry.tenantId(), entry.allowed(), entry.reason(),
                    entry.policyVersion(), entry.basis());
        } else {
            log.info("AUDIT policy-change tenant={} policyVersion={} basis={}",
                    entry.tenantId(), entry.policyVersion(), entry.basis());
        }
    }

    public List<AuditEntry> entries() {
        return new ArrayList<>(entries);
    }

    /** 最近的 n 条（按发生顺序） */
    public List<AuditEntry> recent(int n) {
        int size = entries.size();
        return new ArrayList<>(entries.subList(Math.max(0, size - n), size));
    }
}
