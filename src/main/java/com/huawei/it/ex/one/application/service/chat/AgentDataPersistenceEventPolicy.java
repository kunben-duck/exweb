package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentControlEventMapper;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 在 assistant 占位策略下区分持久化控制事实和仅实时传输的业务事件。 */
final class AgentDataPersistenceEventPolicy {
    private static final Set<String> INTENT_CONTROL_SOURCE_TYPES = Set.of(
            "intent-start",
            "intent-progress",
            "intent-delta",
            "intent-result",
            "intent-clarification-request"
    );
    private static final Set<String> APPLICATION_CONTROL_SOURCE_TYPES = Set.of(
            "selecteddomainagent",
            "route-progress",
            "domain-agent-reroute",
            "intent-clarification-response",
            "clarification-response",
            "route-switch-confirmation-request",
            "route-switch-confirmation-response",
            "route-switch-declined",
            "route-switch-applied",
            "domain-agent-attachment-validation"
    );

    EventRetention retention(ChatEvent event, RunEventPipelineContext context) {
        return retention(event, context == null || context.assistant() == null
                ? null : context.assistant().persistenceState());
    }

    EventRetention retention(ChatEvent event, AgentDataPersistenceState state) {
        if (state == null || !state.placeholderMode()) {
            return EventRetention.PERSISTED;
        }
        if (event instanceof PersistenceAcknowledgedEvent
                || runLifecycleEvent(event)
                || trustedApplicationControlEvent(event)
                || normalizedDomainAgentRefusal(event)
                || relayQuestionnaireRequest(event)) {
            return EventRetention.PERSISTED;
        }
        // 留存策略已经收紧时，未知下游事件按业务输出处理，避免协议扩展后意外写入真实内容。
        return EventRetention.LIVE_ONLY;
    }

    private boolean runLifecycleEvent(ChatEvent event) {
        return event != null && event.type() != null && event.type().startsWith("run.");
    }

    private boolean trustedApplicationControlEvent(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return false;
        }
        String source = text(event.payload().get("source"));
        String sourceType = text(event.payload().get("sourceType"));
        return "intent-agent".equals(source) && INTENT_CONTROL_SOURCE_TYPES.contains(sourceType)
                || "chatservice".equals(source) && APPLICATION_CONTROL_SOURCE_TYPES.contains(sourceType);
    }

    private boolean normalizedDomainAgentRefusal(ChatEvent event) {
        return event != null && "runtime.metadata".equals(event.type())
                && event.payload() != null
                && "domain-agent".equals(text(event.payload().get("source")))
                && DomainAgentControlEventMapper.fromNormalizedPayload(event.payload()).isPresent();
    }

    private boolean relayQuestionnaireRequest(ChatEvent event) {
        if (event == null || !"runtime.card".equals(event.type()) || event.payload() == null
                || !"relay".equals(text(event.payload().get("source")))
                || !RelayQuestionnaireAnswerValidator.isRelayQuestionnaire(event.payload())
                || text(event.payload().get("approval_id")) == null) {
            return false;
        }
        Object questions = event.payload().get("questions");
        return questions instanceof List<?> values && !values.isEmpty();
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    enum EventRetention {
        PERSISTED,
        LIVE_ONLY
    }
}
