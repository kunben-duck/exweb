package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

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
     * @param memory SuperAgent 可选记忆上下文；长短期记忆关闭时为空上下文。
     * @param user 当前用户身份上下文。
     * @return 意图识别结果；服务不可用时由调用方降级到 AgentRuntime。
     */
    IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user);

    default IntentDecision recognize(ChatCommand command,
                                     MemoryContext memory,
                                     UserContext user,
                                     String userMessageId) {
        return recognize(command, memory, user);
    }

    /**
     * 识别当前输入并允许意图服务返回多轮澄清状态。
     *
     * <p>旧实现只需要实现 {@link #recognize(ChatCommand, MemoryContext, UserContext)}；新意图服务可覆盖
     * 本方法返回 WAITING_CLARIFICATION。</p>
     *
     * @param command 当前聊天命令。
     * @param memory SuperAgent 可选记忆上下文。
     * @param user 当前用户身份上下文。
     * @return 意图路由阶段结果。
     */
    default IntentRecognitionResult recognizeForRouting(ChatCommand command, MemoryContext memory, UserContext user) {
        IntentDecision decision = recognize(command, memory, user);
        return IntentRecognitionResult.finalDecision(decision);
    }

    default IntentRecognitionResult recognizeForRouting(ChatCommand command,
                                                         MemoryContext memory,
                                                         UserContext user,
                                                         String userMessageId) {
        return recognizeForRouting(command, memory, user);
    }
}
