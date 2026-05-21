package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * 当前服务实例内正在执行的后台 run 订阅注册表。
 *
 * <p>Redis cancel flag 负责跨 JVM 协调；本注册表只用于命中当前 JVM 时立即 dispose
 * 原始 Runtime/SubAgent 流订阅，减少后端资源消耗和迟到事件。</p>
 */
@Component
public class LocalChatRunExecutionRegistry {
    private final Map<String, Entry> running = new ConcurrentHashMap<>();

    /**
     * 注册当前 JVM 内的 run 执行订阅。
     *
     * @param runId run 标识。
     * @param disposable Reactor subscription，可为空。
     */
    public void register(String runId, Disposable disposable) {
        register(runId, disposable, null);
    }

    /**
     * 注册当前 JVM 内的 run 执行订阅及其写入 claim。
     *
     * @param runId run 标识。
     * @param disposable Reactor subscription，可为空。
     * @param claim 当前执行流持有的写入权声明，可为空。
     */
    public void register(String runId, Disposable disposable, RunExecutionClaim claim) {
        if (runId == null || runId.isBlank() || disposable == null) {
            return;
        }
        running.compute(runId, (ignored, current) ->
                new Entry(disposable, claim == null && current != null ? current.claim() : claim));
    }

    /**
     * 为已经启动但尚未拿到 subscription 的 run 登记写入 claim。
     *
     * <p>后台 run 的 Reactor subscription 和执行租约 claim 产生时机不同。该方法允许主编排先登记 claim，
     * 稍后再由 {@link #register(String, Disposable)} 补齐 subscription。</p>
     *
     * @param claim 当前执行流写入权声明。
     */
    public void registerClaim(RunExecutionClaim claim) {
        if (claim == null || claim.runId() == null || claim.runId().isBlank()) {
            return;
        }
        running.compute(claim.runId(), (ignored, current) ->
                new Entry(current == null ? null : current.disposable(), claim));
    }

    /**
     * 尝试取消当前 JVM 内的 run 执行。
     *
     * @param runId run 标识。
     * @return 是否命中当前 JVM subscription。
     */
    public boolean cancel(String runId) {
        Entry entry = running.remove(runId);
        if (entry == null || entry.disposable() == null) {
            return false;
        }
        entry.disposable().dispose();
        return true;
    }

    /**
     * 返回当前 JVM 内仍在执行的 claim 快照。
     *
     * @return 本机运行中的执行 claim。
     */
    public List<RunExecutionClaim> activeClaims() {
        return running.values().stream()
                .map(Entry::claim)
                .filter(claim -> claim != null && claim.runId() != null)
                .toList();
    }

    /**
     * run 结束后清理订阅引用。
     */
    public void complete(String runId) {
        if (runId != null) {
            running.remove(runId);
        }
    }

    private record Entry(Disposable disposable, RunExecutionClaim claim) {
    }
}
