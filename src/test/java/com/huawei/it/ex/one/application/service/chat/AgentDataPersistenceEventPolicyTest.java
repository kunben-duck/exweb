/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.RunStartedEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class AgentDataPersistenceEventPolicyTest {

    @Test
    void runStartedWithUserMessageIdRemainsPersistedInFullAndPlaceholderModes() {
        RunStartedEvent event = RunStartedEvent.of("run1", "session1", "msg-user");
        for (AgentDataPersistenceState state : List.of(AgentDataPersistenceState.full(),
                new AgentDataPersistenceState("回答已隐藏")
                        .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER))) {
            assertThat(new AgentDataPersistenceEventPolicy().retention(event, state))
                    .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.PERSISTED);
        }
        assertThat(event.payload()).containsEntry("userMessageId", "msg-user");
    }

    @Test
    void placeholderModePersistsTrustedAttachmentValidationEvents() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        RuntimeEvent event = RuntimeEvent.card("run-1", "session-1", Map.of(
                "source", "chatservice",
                "sourceType", "domain-agent-attachment-validation",
                "code", "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED"));

        assertThat(new AgentDataPersistenceEventPolicy().retention(event, state))
                .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.PERSISTED);
    }

    @Test
    void similarlyNamedUntrustedEventRemainsLiveOnly() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        RuntimeEvent event = RuntimeEvent.card("run-1", "session-1", Map.of(
                "source", "domain-agent",
                "sourceType", "domain-agent-attachment-validation",
                "code", "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED"));

        assertThat(new AgentDataPersistenceEventPolicy().retention(event, state))
                .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.LIVE_ONLY);
    }

    @Test
    void placeholderModePersistsTrustedCandidateSwitchMarkerForResume() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        RuntimeEvent event = RuntimeEvent.progress("run-1", "session-1", Map.of(
                "source", "chatservice",
                "sourceType", "candidate-skill-switch",
                "skillId", "skill-b"));

        assertThat(new AgentDataPersistenceEventPolicy().retention(event, state))
                .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.PERSISTED);
    }
}
