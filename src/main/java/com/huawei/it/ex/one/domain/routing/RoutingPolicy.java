package com.huawei.it.ex.one.domain.routing;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

/**
 * 聊天请求路由策略。
 *
 * <p>RoutingPolicy 只做路线裁决；Runtime 多轮续接由 RuntimeBinding 处理，用例库和意图服务只负责提供路由信号。</p>
 *
 * <p>这里故意不调用任何外部服务，也不创建 binding。它是纯领域策略，便于单元测试覆盖各种命中、
 * unsupported 分支。</p>
 */
public class RoutingPolicy {
    /** 用例库命中进入 DomainAgent fast path 的最低分数。 */
    private final double useCaseMinScore;
    /** 保留意图置信度阈值仅用于记录和兼容旧统计，不参与 DomainAgent 裁决。 */
    private final double intentConfidenceThreshold;
    /** Intent accessName 归一化后的专家角色解析器。 */
    private final DomainExpertAccessNameResolver domainExpertResolver;
    /** Intent accessName 归一化后的敏感信息解析器。 */
    private final SensitiveInformationAccessNameResolver sensitiveInformationResolver;

    public RoutingPolicy(double useCaseMinScore) {
        this(useCaseMinScore, 0.85, "", new SensitiveInformationAccessNameResolver(""));
    }

    public RoutingPolicy(double useCaseMinScore, double intentConfidenceThreshold) {
        this(useCaseMinScore, intentConfidenceThreshold, "", new SensitiveInformationAccessNameResolver(""));
    }

    public RoutingPolicy(double useCaseMinScore, double intentConfidenceThreshold,
                         String domainExpertAccessNamePrefix) {
        this(useCaseMinScore, intentConfidenceThreshold, domainExpertAccessNamePrefix,
                new SensitiveInformationAccessNameResolver(""));
    }

    public RoutingPolicy(double useCaseMinScore, double intentConfidenceThreshold,
                         String domainExpertAccessNamePrefix,
                         SensitiveInformationAccessNameResolver sensitiveInformationResolver) {
        this.useCaseMinScore = useCaseMinScore;
        this.intentConfidenceThreshold = intentConfidenceThreshold;
        this.domainExpertResolver = new DomainExpertAccessNameResolver(domainExpertAccessNamePrefix);
        this.sensitiveInformationResolver = sensitiveInformationResolver == null
                ? new SensitiveInformationAccessNameResolver("")
                : sensitiveInformationResolver;
    }

    public double intentConfidenceThreshold() {
        return intentConfidenceThreshold;
    }

    public RouteTarget decideFromUseCase(UseCaseMatchResult match) {
        // 用例库命中代表已有业务样例可以直接确定 DomainAgent。只有同时满足 matched、分数阈值、
        // domainAgentId 非空时才走 fast path，避免“弱命中”错误绑定到下游 Agent。
        if (match != null && match.accepted(useCaseMinScore)) {
            return RouteTarget.domainAgent(match.domainAgentId(), "use-case-library", match.score(), "use case matched");
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

        // 新意图服务以 routeAction 作为最终裁决。ROUTE_SINGLE 会在 adapter 层映射为
        // simpleTask + candidateDomainAgentId=normalized(accessName)；confidence 只用于记录和排障，不再二次拦截。
        if (intent.simpleTask()
                && intent.candidateDomainAgentId() != null
                && !intent.candidateDomainAgentId().isBlank()) {
            if (sensitiveInformationResolver.matches(intent.candidateDomainAgentId())) {
                return RouteTarget.agentRuntime("intent-agent", intent.confidence(),
                        "route single sensitive information intent", RuntimeProfile.DELEGATE);
            }
            DomainExpertAccessNameResolver.Resolution expert = domainExpertResolver.resolve(
                    intent.candidateDomainAgentId());
            if (expert.malformedDomainExpert()) {
                throw new IllegalArgumentException("Domain expert accessName has no roleName");
            }
            if (expert.validDomainExpert()) {
                return RouteTarget.agentRuntime("intent-agent", intent.confidence(),
                        "route single domain expert intent", RuntimeProfile.DOMAIN_EXPERT,
                        expert.roleName());
            }
            return RouteTarget.domainAgent(intent.candidateDomainAgentId(), "intent-agent", intent.confidence(),
                    "route single domain agent intent");
        }

        return RouteTarget.agentRuntime("intent-agent", intent.confidence(), "intent requires agent runtime");
    }
}
