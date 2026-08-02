package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;

import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

/** Runtime 调用前的 assistant 留存策略栅栏。 */
@Component
public class AgentDataPersistenceGate {
    private final AgentDataPersistencePolicyService policyService;

    public AgentDataPersistenceGate(AgentDataPersistencePolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * 仅 DomainAgent 使用可信 skillId 查询配置；Relay 和系统响应第一版固定为 FULL。
     */
    public Mono<AgentDataPersistenceState> resolve(
            UserContext user,
            RouteTarget route,
            AgentDataPersistenceState state) {
        AgentDataPersistenceState targetState = state == null
                ? new AgentDataPersistenceState(policyService.placeholderContent())
                : state;
        targetState.usePlaceholderContent(policyService.placeholderContent());
        if (!policyService.enabled() || route == null || route.type() != RouteType.DOMAIN_AGENT) {
            return Mono.just(targetState);
        }
        String skillId = route.selectedAgentCode();
        if (skillId == null || skillId.isBlank()) {
            return Mono.error(new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "Resolved DomainAgent route has no skillId"));
        }
        return policyService.resolve(user, skillId)
                .map(targetState::tighten);
    }
}
