/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class StandardRunInputPreparerTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "preceding-answer")
    void historicalCandidateMemoryEndsBeforeOriginalUserAndReusesResolvedAttachments(String precedingAnswer) {
        UserContext user = new UserContext("tenant1", "user1", "User");
        Instant now = Instant.now();
        ChatSession session = new ChatSession("session1", "tenant1", "user1", "title", "ACTIVE", "web",
                null, null, "later-answer", "session1", null, null, 6L, null, now, now);
        ChatMessage original = new ChatMessage("original-user", "tenant1", "user1", "session1", precedingAnswer,
                1L, 0, 1, "user", "可信原问题", null, "run-source", "NORMAL", false,
                null, null, null, null, "{\"oldMetadata\":true}", now);
        AttachmentRef attachment = new AttachmentRef("doc1", "trusted.pdf", "application/pdf", 20L);
        UploadedDocument document = mock(UploadedDocument.class);
        CandidateSwitchRunSource source = new CandidateSwitchRunSource("run-source", ChatRunStatus.COMPLETED,
                session, original, "original-answer", null,
                new ResolvedChatAttachments(List.of(attachment), List.of(document)),
                CandidateSwitchRouteTrace.empty(), "later-answer");
        ChatCommand command = new ChatCommand("command", "tenant1", "user1", "session1", null, "web",
                "不应使用的请求正文", List.of(), Map.of("currentOnly", true), "DOMAIN_AGENT", "skill-b",
                ChatRunMode.REGENERATE_ASSISTANT, null, null, "original-answer");
        RunMemoryContextAssembler memory = mock(RunMemoryContextAssembler.class);
        boolean emptyHistory = precedingAnswer == null;
        when(memory.assemble(any(), eq(precedingAnswer), eq(emptyHistory))).thenReturn(MemoryContext.empty());
        SessionApplicationService sessions = mock(SessionApplicationService.class);
        DocumentFacade documents = mock(DocumentFacade.class);
        ChatInteractionApplicationService interactions = mock(ChatInteractionApplicationService.class);
        StandardRunInputPreparer preparer = new StandardRunInputPreparer(sessions, memory, documents, interactions,
                mock(ChatRunApplicationService.class), (type, context) -> "run-new",
                mock(ChatRunStartCoordinator.class), mock(ChatRunAdmissionCoordinator.class));

        var prepared = preparer.prepareCandidateSwitch(new StandardRunInputPreparer.Request(
                user, TraceContext.empty(), command, RuntimeForwardHeaders.empty(), null), source);

        verify(memory).assemble(any(), eq(precedingAnswer), eq(emptyHistory));
        assertThat(prepared.command().message()).isEqualTo(original.content());
        assertThat(prepared.command().metadata()).containsEntry("currentOnly", true).doesNotContainKey("oldMetadata");
        assertThat(prepared.attachments()).containsExactly(attachment);
        assertThat(prepared.documents()).containsExactly(document);
        assertThat(prepared.explicitRuntimeTarget().targetId()).isEqualTo("skill-b");
        verifyNoInteractions(sessions, documents, interactions);
    }
}
