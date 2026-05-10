package com.huawei.finance.front.one.application.integration.agent;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;

/**
 * 复杂任务统一 Runtime 抽象。
 *
 * <p>当前正式版本只保留 RelayAgent Runtime。SuperAgent 通过该接口提交复杂任务查询，
 * Relay Runtime 自己负责规划、上下文、压缩和内部会话管理。</p>
 */
public interface AgentRuntime {
    /**
     * 向 Relay Runtime 提交一轮查询。
     *
     * @param request Runtime 查询请求。
     * @return 标准聊天事件流。
     */
    Flux<ChatEvent> query(AgentRuntimeRequest request);
}
