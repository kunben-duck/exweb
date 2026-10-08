/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

class DomainAgentAttachmentValidationFailureExecutorTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void countFailureRemainsAControlFactInFullAndNoStoreHistory(boolean noStore) {
        String message = "该技能最多支持上传2个附件，本次上传3个，请减少附件后重试。";
        Map<String, Object> common = Map.of(
                "source", "chatservice",
                "sourceType", "domain-agent-attachment-validation",
                "code", "DOMAIN_AGENT_ATTACHMENT_COUNT_EXCEEDED",
                "skillId", "skill-1",
                "skillName", "技能一",
                "actualAttachmentCount", 3,
                "maxAttachmentCount", 2,
                "limitSource", "SKILL",
                "message", message);
        AgentDataPersistenceState state = AgentDataPersistenceState.full();
        if (noStore) {
            state.tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        }
        AssistantAssembly assembly = new AssistantAssembly(state);
        List<ChatEvent> events = new DomainAgentAttachmentValidationFailureExecutor()
                .execute("run-1", "session-1", common).collectList().block();

        assertThat(events).extracting(ChatEvent::type)
                .containsExactly("runtime.progress", "runtime.card", "message.completed");
        events.forEach(assembly::observe);
        assertThat(events.get(0).payload()).containsAllEntriesOf(common)
                .containsEntry("stage", "attachment_validation").containsEntry("status", "FAILED");
        assertThat(events.get(1).payload()).containsAllEntriesOf(common)
                .containsEntry("cardType", "domainAgentAttachmentUnsupported");
        AgentDataPersistenceEventPolicy policy = new AgentDataPersistenceEventPolicy();
        assertThat(events.subList(0, 2)).allSatisfy(event -> assertThat(policy.retention(event, state))
                .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.PERSISTED));
        assertThat(events.get(2).payload()).containsEntry("finishReason", "ATTACHMENT_COUNT_EXCEEDED")
                .containsEntry("skillInvocationStarted", false);
        assertThat(assembly.parts()).extracting(part -> part.partType()).containsExactly("PROGRESS", "CARD");
        assertThat(assembly.parts().getFirst().contentText()).isEqualTo(message);
        assertThat(assembly.finalContent()).isEqualTo(noStore ? state.placeholderContent() : "");
    }

    @Test
    void emitsStableBusinessCompletionEventsWithoutAnswerDelta() {
        Map<String, Object> common = Map.of(
                "source", "chatservice",
                "sourceType", "domain-agent-attachment-validation",
                "code", "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED",
                "skillId", "skill-1",
                "skillName", "技能一",
                "supportedAttachmentTypes", List.of(".xlsx"),
                "unsupportedAttachmentTypes", List.of(".pdf"),
                "unsupportedAttachments", List.of(Map.of(
                        "documentId", "doc-1", "name", "report.pdf", "extension", ".pdf")));

        List<ChatEvent> events = new DomainAgentAttachmentValidationFailureExecutor()
                .execute("run-1", "session-1", common)
                .collectList()
                .block();

        assertThat(events).extracting(ChatEvent::type)
                .containsExactly("runtime.progress", "runtime.card", "message.completed");
        assertThat(events.get(0).payload())
                .containsEntry("stage", "attachment_validation")
                .containsEntry("status", "FAILED")
                .containsEntry("skillId", "skill-1");
        assertThat(events.get(1).payload())
                .containsEntry("cardType", "domainAgentAttachmentUnsupported")
                .containsEntry("cardSources", List.of("attachmentValidation"));
        assertThat(events.get(2).payload())
                .containsEntry("status", "MESSAGE_COMPLETED")
                .containsEntry("finishReason", "ATTACHMENT_TYPE_UNSUPPORTED")
                .containsEntry("skillInvocationStarted", false);
    }
}
