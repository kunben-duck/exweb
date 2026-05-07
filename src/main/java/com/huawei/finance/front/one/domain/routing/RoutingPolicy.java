package com.huawei.finance.front.one.domain.routing;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;

/**
 * 聊天请求路由策略。
 *
 * <p>RoutingPolicy 只做路线裁决；多轮续接由 AgentBinding 优先处理，用例库和意图服务只负责提供路由信号。</p>
 *
 * <p>这里故意不调用任何外部服务，也不创建 binding。它是纯领域策略，便于单元测试覆盖各种命中、
 * 低置信和 unsupported 分支。</p>
 */
public class RoutingPolicy {
    /** 意图服务简单任务进入 SubAgent fast path 的最低置信度。 */
    private static final double FAST_PATH_CONFIDENCE = 0.85;
    /** 用例库命中进入 SubAgent fast path 的最低分数。 */
    private final double useCaseMinScore;

    public RoutingPolicy(double useCaseMinScore) {
        this.useCaseMinScore = useCaseMinScore;
    }

    public RouteTarget decideFromUseCase(UseCaseMatchResult match) {
        // 用例库命中代表已有业务样例可以直接确定 SubAgent。只有同时满足 matched、分数阈值、
        // subAgentCode 非空时才走 fast path，避免“弱命中”错误绑定到下游 Agent。
        if (match != null && match.accepted(useCaseMinScore)) {
            return RouteTarget.subAgent(match.subAgentCode(), "use-case-library", match.score(), "use case matched");
        }
        return RouteTarget.agentRuntime("use-case-library", match == null ? 0.0 : match.score(), "use case not matched");
    }

    public RouteTarget decideFromIntent(ChatCommand command, MemoryContext memory, IntentDecision intent, UserContext user) {
        if (intent == null) {
            return RouteTarget.agentRuntime("intent decision missing");
        }

        // 明确 unsupported 的请求不进入复杂 Agent 规划，直接返回系统可控说明。
        if (intent.complexity() == TaskComplexity.UNSUPPORTED) {
            return RouteTarget.systemResponse("unsupported intent");
        }

        // 意图服务只在“简单任务 + 高置信 + 明确 SubAgent”时触发 SubAgent。
        // 缺少任一条件都交给 AgentRuntime，由它负责追问、规划或兜底回答。
        if (intent.simpleTask()
                && intent.highConfidence(FAST_PATH_CONFIDENCE)
                && intent.candidateSubAgentCode() != null
                && !intent.candidateSubAgentCode().isBlank()) {
            return RouteTarget.subAgent(intent.candidateSubAgentCode(), "intent-service", intent.confidence(), "high confidence subagent intent");
        }

        return RouteTarget.agentRuntime("intent-service", intent.confidence(), "intent requires agent runtime");
    }
}
