package com.huawei.it.ex.one.intent.application.model;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;

/**
 * 意图识别重试策略的判定上下文。
 *
 * <p>该对象在调用线程内构造，只包含本轮请求、识别结果和尝试次数快照。策略实现不应读取
 * HTTP request、企业 ThreadLocal 或其他入口上下文，避免 Servlet/MVC 异步场景下身份串扰。</p>
 *
 * @param command 本轮聊天命令。
 * @param memory 本轮可用记忆上下文。
 * @param user 请求入口固化后的用户上下文。
 * @param decision 本次意图识别结果；为空表示本次未得到有效决策。
 * @param attempt 当前尝试次数，从 1 开始。
 * @param maxAttempts 本轮最多尝试次数。
 */
public record IntentRetryContext(
        IntentCommandSnapshot command,
        MemoryContext memory,
        UserContext user,
        IntentDecision decision,
        int attempt,
        int maxAttempts
) {
    /**
     * 当前判定点之后是否仍有可用重试次数。
     *
     * @return true 表示还可以再发起一次意图识别调用。
     */
    public boolean hasRemainingAttempts() {
        return attempt < maxAttempts;
    }
}
