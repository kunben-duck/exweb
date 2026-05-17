package com.huawei.finance.front.one.application.integration.intent;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;

/**
 * 意图识别服务。
 *
 * <p>当前生产实现通过 HTTP API 接入第三方意图服务。该端口只返回结构化识别结果，
 * 不直接执行下游 Agent，也不承担 Agent 的复杂多轮规划职责。</p>
 */
public interface IntentService {
    /**
     * 识别当前用户输入的任务意图。
     *
     * @param command 当前聊天命令。
     * @param memory SuperAgent 装配的会话上下文快照。
     * @param user 当前用户身份上下文。
     * @return 意图识别结果；服务不可用时由调用方降级到 AgentRuntime。
     */
    IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user);
}
