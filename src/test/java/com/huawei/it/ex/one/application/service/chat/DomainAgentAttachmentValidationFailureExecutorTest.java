/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class DomainAgentAttachmentValidationFailureExecutorTest {

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
