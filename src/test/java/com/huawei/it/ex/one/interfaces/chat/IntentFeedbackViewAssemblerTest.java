/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.facade.ChatSessionFacade;
import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackRepository;
import com.huawei.it.ex.one.application.service.chat.ChatFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentFeedbackHistoryService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessageDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessagePartDto;
import com.huawei.it.ex.one.interfaces.chat.dto.IntentFeedbackDto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

class IntentFeedbackViewAssemblerTest {
    private static final UserContext USER = new UserContext("t", "u", "u");
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void productionControllerEnrichesMessagesVariantsAndTree() {
        ChatSessionFacade facade = mock(ChatSessionFacade.class);
        IntentFeedbackHistoryService history = mock(IntentFeedbackHistoryService.class);
        when(history.find(eq(USER), eq("s"), any())).thenReturn(Map.of("a", feedback("a")));
        ChatMessage message = new ChatMessage("m", "t", "u", "s", "q", 1L, 1, 0, "assistant",
                "answer", null, "a", "NORMAL", false, null, null, null, null, "metadata", NOW);
        when(facade.listMessages(USER, "s", null, null, 50))
                .thenReturn(new ChatMessagePage(List.of(message), null));
        when(facade.listVariants(USER, "s", "m")).thenReturn(List.of(message));
        when(facade.listMessageTree(USER, "s")).thenReturn(List.of(message));
        when(facade.getSession(USER, "s")).thenReturn(new ChatSession("s", "t", "u", "title", "ACTIVE", "web", NOW, NOW));
        ChatSessionController controller = new ChatSessionController(facade,
                mock(ChatFeedbackApplicationService.class), mock(ChatRunApplicationService.class),
                () -> USER, new PermissionChecker(), new ChatMessageVersionViewAssembler(),
                new IntentFeedbackViewAssembler(history));
        assertThat(controller.messages("s", null, null, 50).block().items().getFirst().intentFeedback().runId())
                .isEqualTo("a");
        assertThat(controller.variants("s", "m").block().getFirst().intentFeedback().runId()).isEqualTo("a");
        assertThat(controller.messageTree("s").block().mapping().get("m").message().intentFeedback().runId())
                .isEqualTo("a");
        verify(history, org.mockito.Mockito.times(3)).find(USER, "s", Set.of("a"));
    }

    @Test
    void inheritedNodesUseOriginalRunsAndDoNotMutateOriginalPayloads() {
        IntentFeedbackHistoryService history = mock(IntentFeedbackHistoryService.class);
        when(history.find(eq(USER), eq("s"), any())).thenReturn(Map.of("a", feedback("a"), "b", feedback("b")));
        ChatMessagePartDto oldA = part("a1", "c", "selectedDomainAgent", Map.of(
                "candidateSwitchReplay", Map.of("originRunId", "a", "originSequence", 2)));
        ChatMessagePartDto latestA = part("a2", "c", "selectedDomainAgent", Map.of(
                "candidateSwitchReplay", Map.of("originRunId", "a", "originSequence", 3)));
        ChatMessagePartDto oldB = part("b1", "c", "selectedDomainAgent", Map.of(
                "candidateSwitchReplay", Map.of("originRunId", "b", "originSequence", 10)));
        ChatMessageDto original = message("c", "NORMAL", false, List.of(oldA, latestA, oldB,
                part("c1", "c", "selectedDomainAgent", Map.of("intentName", "C"))));
        ChatMessageDto result = new IntentFeedbackViewAssembler(history).enrich(USER, "s", List.of(original)).getFirst();
        verify(history).find(USER, "s", Set.of("a", "b", "c"));
        assertThat(result.intentFeedback()).isNull();
        assertThat(result.parts().get(0).payload()).doesNotContainKey("intentFeedback");
        assertThat(((IntentFeedbackDto) result.parts().get(1).payload().get("intentFeedback")).runId()).isEqualTo("a");
        assertThat(((IntentFeedbackDto) result.parts().get(2).payload().get("intentFeedback")).runId()).isEqualTo("b");
        assertThat(result.parts().get(3).payload()).doesNotContainKey("intentFeedback");
        assertThat(original.parts()).allSatisfy(part -> assertThat(part.payload()).doesNotContainKey("intentFeedback"));
        assertThat(result.content()).isEqualTo(original.content());
        assertThat(result.metadataJson()).isEqualTo(original.metadataJson());
        assertThat(result.createdAt()).isEqualTo(original.createdAt());
    }

    @Test
    void switchMarkerFallbackUsesSourceNotEmittingRunAndOwnFeedbackIsSeparate() {
        IntentFeedbackHistoryService history = mock(IntentFeedbackHistoryService.class);
        when(history.find(eq(USER), eq("s"), any())).thenReturn(Map.of("a", feedback("a"), "b", feedback("b")));
        ChatMessageDto result = new IntentFeedbackViewAssembler(history).enrich(USER, "s", List.of(
                message("b", "NORMAL", false, List.of(part("marker", "b", "candidate-skill-switch",
                        Map.of("sourceRunId", "a")))))).getFirst();
        assertThat(result.intentFeedback().runId()).isEqualTo("b");
        assertThat(((IntentFeedbackDto) result.parts().getFirst().payload().get("intentFeedback")).runId()).isEqualTo("a");
    }

    @Test
    void noStoreWithoutPartsUsesMessageFeedbackAndFailureLeavesHistoryUnchanged() {
        IntentFeedbackHistoryService history = mock(IntentFeedbackHistoryService.class);
        when(history.find(eq(USER), eq("s"), any())).thenReturn(Map.of("a", feedback("a")));
        ChatMessageDto original = message("a", "NORMAL", false, List.of());
        IntentFeedbackViewAssembler assembler = new IntentFeedbackViewAssembler(history);
        assertThat(assembler.enrich(USER, "s", List.of(original)).getFirst().intentFeedback().runId()).isEqualTo("a");
        when(history.find(eq(USER), eq("s"), any())).thenThrow(new IllegalStateException("read timeout"));
        assertThat(assembler.enrich(USER, "s", List.of(original)).getFirst()).isSameAs(original);
    }

    @Test
    void branchSnapshotsAndEmptyPagesDoNotQueryOrCopyFeedback() {
        IntentFeedbackHistoryService history = mock(IntentFeedbackHistoryService.class);
        IntentFeedbackViewAssembler assembler = new IntentFeedbackViewAssembler(history);
        assertThat(assembler.enrich(USER, "s", List.of())).isEmpty();
        ChatMessageDto snapshot = message("a", "BRANCH_SNAPSHOT", true, List.of());
        assertThat(assembler.enrich(USER, "s", List.of(snapshot)).getFirst()).isSameAs(snapshot);
        verify(history, never()).find(any(), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void historyBatchesAt200AndUsesOneTwoSecondReadOnlyTransaction() throws Exception {
        IntentFeedbackRepository repository = mock(IntentFeedbackRepository.class);
        when(repository.findByOwnerAndRuns(any(), any(), any())).thenReturn(List.of());
        IntentFeedbackHistoryService history = new IntentFeedbackHistoryService(repository);
        history.find(USER, "s", IntStream.range(0, 401).mapToObj(i -> "r" + i).toList());
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass((Class) List.class);
        verify(repository, org.mockito.Mockito.times(3)).findByOwnerAndRuns(eq("t"), eq("u"), ids.capture());
        assertThat(ids.getAllValues()).extracting(List::size).containsExactly(200, 200, 1);
        Transactional transaction = IntentFeedbackHistoryService.class.getMethod(
                "find", UserContext.class, String.class, Collection.class).getAnnotation(Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.timeout()).isEqualTo(2);
    }

    @Test
    void publicDtoDoesNotLeakOwnershipOrChangeExistingMetadata() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ChatMessageDto result = message("a", "NORMAL", false, List.of())
                .withIntentFeedback(IntentFeedbackDto.from(feedback("a")), List.of());
        String json = mapper.writeValueAsString(result);
        assertThat(json).contains("\"intentFeedback\"", "\"feedbackType\":\"CORRECT\"");
        assertThat(json).doesNotContain("tenantId", "sourceMessageId\":\"q\"", "\"userId\"");
        assertThat(mapper.readTree(json).get("metadataJson").asText()).isEqualTo("{\"skillId\":\"a\"}");
    }

    private ChatMessageDto message(String run, String origin, boolean locked, List<ChatMessagePartDto> parts) {
        return new ChatMessageDto("m-" + run, "s", "q", 1L, 1, 0, "assistant", "answer", null, run,
                "domain-agent", origin, locked, null, null, null, null, "{\"skillId\":\"a\"}",
                parts, List.of(), null, null, NOW);
    }

    private ChatMessagePartDto part(String id, String run, String type, Map<String, Object> payload) {
        return new ChatMessagePartDto(id, "m-" + run, run, "METADATA", type, null, "title", "INFO",
                "metadata", "inline", true, payload, 1, NOW);
    }

    private IntentFeedback feedback(String run) {
        return new IntentFeedback("fb-" + run, "t", "u", "s", run, "q", "fin", "CORRECT",
                null, null, null, "id", "name", NOW);
    }
}
