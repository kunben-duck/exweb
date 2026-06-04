package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamLogEntry;

/**
 * Runtime 原始流响应日志仓储端口。
 *
 * <p>该端口服务排障日志，不参与 run 生命周期状态迁移；实现层必须保证写入失败不会污染主链路。</p>
 */
public interface RuntimeRawStreamLogRepository {
    /**
     * 保存一行原始流响应日志。
     *
     * @param entry 原始流日志行。
     */
    void save(RuntimeRawStreamLogEntry entry);
}
