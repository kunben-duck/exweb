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
    IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user);
}
