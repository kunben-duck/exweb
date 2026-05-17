package com.huawei.finance.front.one.application.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * 当前 JVM 内正在执行的后台 run 订阅注册表。
 *
 * <p>Redis cancel flag 负责跨 JVM 协调；本注册表只用于命中当前 JVM 时立即 dispose
 * 原始 Runtime/SubAgent 流订阅，减少后端资源消耗和迟到事件。</p>
 */
@Component
public class ChatRunExecutionRegistry {
    private final Map<String, Disposable> running = new ConcurrentHashMap<>();

    /**
     * 注册当前 JVM 内的 run 执行订阅。
     *
     * @param runId run 标识。
     * @param disposable Reactor subscription，可为空。
     */
    public void register(String runId, Disposable disposable) {
        if (runId == null || runId.isBlank() || disposable == null) {
            return;
        }
        running.put(runId, disposable);
    }

    /**
     * 尝试取消当前 JVM 内的 run 执行。
     *
     * @param runId run 标识。
     * @return 是否命中当前 JVM subscription。
     */
    public boolean cancel(String runId) {
        Disposable disposable = running.remove(runId);
        if (disposable == null) {
            return false;
        }
        disposable.dispose();
        return true;
    }

    /**
     * run 结束后清理订阅引用。
     */
    public void complete(String runId) {
        if (runId != null) {
            running.remove(runId);
        }
    }
}
