/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.service.domainagentconfig.DomainAgentSkillConfigurationService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;

import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Runtime 调用前的 assistant 留存策略栅栏。 */
@Component
public class AgentDataPersistenceGate {
    private static final AppLogger log = AppLoggerFactory.getLogger(AgentDataPersistenceGate.class);

    private final AgentDataPersistencePolicyService policyService;
    private final DomainAgentSkillConfigurationService configurationService;
    private final DomainAgentProperties domainAgentProperties;
    private final DomainAgentAttachmentTypeValidator attachmentValidator =
            new DomainAgentAttachmentTypeValidator();

    public AgentDataPersistenceGate(
            AgentDataPersistencePolicyService policyService,
            DomainAgentSkillConfigurationService configurationService,
            DomainAgentProperties domainAgentProperties) {
        this.policyService = policyService;
        this.configurationService = configurationService;
        this.domainAgentProperties = domainAgentProperties == null
                ? new DomainAgentProperties()
                : domainAgentProperties;
    }

    /**
     * 仅 DomainAgent 使用可信 skillId 查询配置；Relay 和系统响应第一版固定为 FULL。
     */
    public Mono<AgentDataPersistenceState> resolve(
            UserContext user,
            RouteTarget route,
            AgentDataPersistenceState state,
            RuntimeForwardHeaders forwardHeaders) {
        return evaluate(user, route, state, forwardHeaders, List.of())
                .map(Decision::state);
    }

    /** 保留不需要出站请求头的内部调用兼容入口。 */
    public Mono<AgentDataPersistenceState> resolve(
            UserContext user,
            RouteTarget route,
            AgentDataPersistenceState state) {
        return resolve(user, route, state, RuntimeForwardHeaders.empty());
    }

    /** 在同一次技能配置解析中完成留存策略和附件类型校验。 */
    public Mono<Decision> evaluate(
            UserContext user,
            RouteTarget route,
            AgentDataPersistenceState state,
            RuntimeForwardHeaders forwardHeaders,
            List<UploadedDocument> documents) {
        AgentDataPersistenceState targetState = state == null
                ? new AgentDataPersistenceState(policyService.placeholderContent())
                : state;
        targetState.usePlaceholderContent(policyService.placeholderContent());
        if (route == null || route.type() != RouteType.DOMAIN_AGENT) {
            return Mono.just(Decision.allowed(targetState));
        }
        validateAttachmentCount(documents);
        String skillId = route.selectedAgentCode();
        if (skillId == null || skillId.isBlank()) {
            return Mono.error(new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "Resolved DomainAgent route has no skillId"));
        }
        boolean attachmentCheckRequired = attachmentValidator.requiresConfiguration(documents);
        if (!policyService.enabled() && !attachmentCheckRequired) {
            return Mono.just(Decision.allowed(targetState));
        }
        return configurationService.resolve(user, skillId, forwardHeaders)
                .map(configuration -> {
                    if (policyService.enabled()) {
                        targetState.tighten(policyService.resolve(skillId, configuration));
                    }
                    DomainAgentAttachmentTypeValidator.Validation validation =
                            attachmentValidator.validate(configuration, documents);
                    if (validation.malformed()) {
                        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.CONFIGURATION_INVALID,
                                        "DomainAgent attachment type configuration is invalid; validation is skipped")
                                .operation("domain-agent-attachment.validate")
                                .attribute("skillId", skillId)
                                .build());
                    }
                    return validation.unsupported()
                            ? Decision.unsupported(
                                    targetState,
                                    attachmentValidator.payload(
                                            skillId, configuration.skillName(), validation))
                            : Decision.allowed(targetState);
                })
                .onErrorResume(DomainAgentSkillConfigurationException.class, error -> {
                    if (policyService.enabled()) {
                        return Mono.error(error);
                    }
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.CONFIGURATION_INVALID,
                                    "DomainAgent skill configuration unavailable; attachment validation is skipped")
                            .operation("domain-agent-attachment.config")
                            .attribute("skillId", skillId)
                            .build(), error);
                    return Mono.just(Decision.allowed(targetState));
                });
    }

    private void validateAttachmentCount(List<UploadedDocument> documents) {
        if (documents != null && documents.size() > domainAgentProperties.normalizedMaxAttachments()) {
            throw new IllegalArgumentException(
                    "DomainAgent 附件数量超过上限: " + domainAgentProperties.normalizedMaxAttachments());
        }
    }

    public record Decision(Status status, AgentDataPersistenceState state, Map<String, Object> payload) {
        public Decision {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }

        public static Decision allowed(AgentDataPersistenceState state) {
            return new Decision(Status.ALLOW, state, Map.of());
        }

        public static Decision unsupported(
                AgentDataPersistenceState state,
                Map<String, Object> payload) {
            return new Decision(Status.UNSUPPORTED_ATTACHMENT, state, payload);
        }

        public boolean unsupportedAttachment() {
            return status == Status.UNSUPPORTED_ATTACHMENT;
        }
    }

    public enum Status {
        ALLOW,
        UNSUPPORTED_ATTACHMENT
    }
}
