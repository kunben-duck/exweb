package com.huawei.finance.front.one.domain.routing;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.SimpleTaskType;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;

/**
 * 聊天请求路由策略。
 *
 * <p>RoutingPolicy 只消费 IntentService 的结构化结果做轻量路线裁决；
 * 复杂槽位追问、ReAct 规划和工具探索交给 AgentRuntime，最终鉴权和参数校验交给 ToolGateway。</p>
 */
public class RoutingPolicy {
    private static final double FAST_PATH_CONFIDENCE = 0.85;

    public RouteTarget decide(ChatCommand command, MemoryContext memory, IntentDecision intent, UserContext user) {
        if (intent == null) {
            return RouteTarget.agentRuntime("intent decision missing");
        }

        // 明确不支持的任务不进入 Agent 规划，交给直接模型响应生成可控说明。
        if (intent.complexity() == TaskComplexity.UNSUPPORTED) {
            return RouteTarget.directModel("unsupported intent");
        }

        // 缺槽、低置信或复杂任务进入统一 AgentRuntime，由 Agent 负责多轮追问和计划。
        if (intent.complexity() == TaskComplexity.NEED_CLARIFICATION
                || intent.complexity() == TaskComplexity.COMPLEX
                || !intent.highConfidence(FAST_PATH_CONFIDENCE)
                || !intent.simpleTask()) {
            return RouteTarget.agentRuntime("intent requires agent runtime");
        }

        if (intent.simpleTaskType() == SimpleTaskType.DIRECT_TOOL && intent.candidateToolCode() != null && !intent.candidateToolCode().isBlank()) {
            return RouteTarget.directTool(intent.candidateToolCode(), "high confidence direct tool");
        }

        if (intent.simpleTaskType() == SimpleTaskType.DIRECT_MODEL || intent.complexity() == TaskComplexity.SIMPLE) {
            return RouteTarget.directModel("high confidence direct model");
        }

        return RouteTarget.agentRuntime("route fallback to agent runtime");
    }
}
