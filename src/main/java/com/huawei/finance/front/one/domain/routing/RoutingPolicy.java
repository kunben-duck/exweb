package com.huawei.finance.front.one.domain.routing;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;

/**
 * Agent 路由策略。
 *
 * <p>当前版本按意图复杂度做最小可用决策：简单任务本地处理，复杂任务转发 Runtime，
 * 澄清和不支持任务直接生成协议事件。后续可在这里接入租户策略、模型成本和工具可用性。</p>
 */
public class RoutingPolicy {
    public RouteTarget decide(ChatCommand command, MemoryContext memory, IntentDecision intent, UserContext user) {
        // 意图识别明确要求补充信息时，不进入 Agent，直接让前端提示用户补充条件。
        if (intent.complexity() == TaskComplexity.NEED_CLARIFICATION) {
            return RouteTarget.clarification("intent requires clarification");
        }
        // 不支持的任务尽早拒绝，避免无意义地消耗 Agent/Runtime 资源。
        if (intent.complexity() == TaskComplexity.UNSUPPORTED) {
            return RouteTarget.reject("unsupported intent");
        }
        // 复杂任务或非简单任务转给 Relay Runtime，通常由更强的 Python/外部 Agent 处理。
        if (intent.complexity() == TaskComplexity.COMPLEX || !intent.simpleTask()) {
            return RouteTarget.relay(RuntimeProtocol.HTTP_STREAM, "complex task relay to runtime");
        }
        return RouteTarget.local("simple task handled by local agent");
    }
}
