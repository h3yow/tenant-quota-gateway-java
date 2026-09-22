package com.github.h3yow.tenantquotagatewayjava.quota;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按租户维护配额窗口用量与请求判定的幂等记录。
 *
 * 每个租户独立加锁，采用“先占坑、后提交”的两段式流程，保证同一 requestId 的并发与重试：
 * 1) 永远得到同一个判定结论（先到请求的结论）；
 * 2) 配额最多被扣减一次；
 * 3) 被拒绝的请求不扣减。
 * 窗口滚动时用量与幂等记录一同清空。
 */
public class QuotaLedger {

    /** 开始处理一个 requestId 的结果：首个请求或拿到历史结论的重放 */
    public sealed interface Begin permits Begin.First, Begin.Replay {
        record First() implements Begin {
        }

        record Replay(boolean allowed, RejectionReason reason) implements Begin {
        }
    }

    private final ConcurrentHashMap<String, TenantWindow> windows = new ConcurrentHashMap<>();

    private TenantWindow window(String tenantId) {
        return windows.computeIfAbsent(tenantId, k -> new TenantWindow());
    }

    /**
     * 声明开始处理某请求。首个调用方拿到 {@link Begin.First}；
     * 重复提交/重试方阻塞至首个结论产生后拿到 {@link Begin.Replay}。
     */
    public Begin begin(String tenantId, String requestId, long windowMillis) {
        if (requestId == null) {
            // 无幂等键的请求不参与去重，每次都视为首个
            window(tenantId).roll(windowMillis);
            return new Begin.First();
        }
        return window(tenantId).begin(requestId, windowMillis);
    }

    /**
     * 首个请求在通过速率限制后原子地尝试占用配额：
     * 成功则提交“允许”结论；配额已满则提交“QUOTA_EXCEEDED”结论。
     */
    public boolean commitQuota(String tenantId, String requestId, long limit, long windowMillis) {
        if (requestId == null) {
            TenantWindow w = window(tenantId);
            synchronized (w) {
                w.roll(windowMillis);
                if (w.used >= limit) {
                    return false;
                }
                w.used++;
                return true;
            }
        }
        return window(tenantId).commitQuota(requestId, limit, windowMillis);
    }

    /**
     * 首个请求在速率限制环节被拒时提交结论，使后续重试得到一致判定且不接触配额。
     */
    public void commitRejection(String tenantId, String requestId, RejectionReason reason, long windowMillis) {
        if (requestId == null) {
            return;
        }
        window(tenantId).commitRejection(requestId, reason, windowMillis);
    }

    /** 首个请求处理异常时放弃占坑，避免后续重试被永久挂起 */
    public void abort(String tenantId, String requestId) {
        if (requestId == null) {
            return;
        }
        TenantWindow w = windows.get(tenantId);
        if (w != null) {
            w.abort(requestId);
        }
    }

    public long currentUsage(String tenantId) {
        TenantWindow w = windows.get(tenantId);
        synchronized (w == null ? new Object() : w) {
            return w == null ? 0 : w.used;
        }
    }

    /** 清空全部计量状态（策略整体替换时配合外部同步使用） */
    public void reset() {
        windows.clear();
    }

    private static final class StoredVerdict {
        final boolean allowed;
        final RejectionReason reason;

        StoredVerdict(boolean allowed, RejectionReason reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }

    private static final class TenantWindow {
        private long windowStart;
        private long used;
        /** requestId -> 占坑标记(null 值=处理中) 或 最终判定 */
        private final Map<String, StoredVerdict> verdicts = new HashMap<>();

        synchronized void roll(long windowMillis) {
            long now = System.currentTimeMillis();
            if (windowStart == 0L) {
                windowStart = now;
            } else if (now - windowStart >= windowMillis) {
                windowStart = now;
                used = 0;
                verdicts.clear();
            }
        }

        synchronized Begin begin(String requestId, long windowMillis) {
            roll(windowMillis);
            StoredVerdict verdict;
            while ((verdict = verdicts.get(requestId)) == null) {
                if (!verdicts.containsKey(requestId)) {
                    // null 值占位：首个请求，正在处理
                    verdicts.put(requestId, null);
                    return new Begin.First();
                }
                // 已有占坑但尚无结论，等待首个请求提交
                try {
                    wait(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // 被中断：保守起见重新抢占，保证调用方能继续并给出确定结论
                    verdicts.put(requestId, null);
                    return new Begin.First();
                }
                roll(windowMillis);
            }
            return new Begin.Replay(verdict.allowed, verdict.reason);
        }

        synchronized boolean commitQuota(String requestId, long limit, long windowMillis) {
            roll(windowMillis);
            if (used >= limit) {
                verdicts.put(requestId, new StoredVerdict(false, RejectionReason.QUOTA_EXCEEDED));
                notifyAll();
                return false;
            }
            used++;
            verdicts.put(requestId, new StoredVerdict(true, RejectionReason.NONE));
            notifyAll();
            return true;
        }

        synchronized void commitRejection(String requestId, RejectionReason reason, long windowMillis) {
            roll(windowMillis);
            verdicts.put(requestId, new StoredVerdict(false, reason));
            notifyAll();
        }

        synchronized void abort(String requestId) {
            // 仅清除仍是“处理中”的占坑，避免覆盖已提交结论
            if (verdicts.containsKey(requestId) && verdicts.get(requestId) == null) {
                verdicts.remove(requestId);
                notifyAll();
            }
        }
    }
}
