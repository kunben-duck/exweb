/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.service.chat.ChatFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.chat.SessionApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentFeedbackHistoryService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessageFeedback;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessageVersionCandidate;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;
import com.huawei.it.ex.one.infrastructure.memory.ChatMessageMapper;
import com.huawei.it.ex.one.infrastructure.memory.ChatMessageRow;
import com.huawei.it.ex.one.infrastructure.memory.MyBatisChatMessageStore;
import com.huawei.it.ex.one.interfaces.ApiExceptionHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

class ChatSessionOperationsHistoryTest {
    private static final String PATH = "/v1/chat/sessions/session/messages";
    private static final String OPERATIONS = PATH + "/operations";
    private static final String MOBILE = "https://example.com/mobile/card.js";
    private final UserContext operator = new UserContext("ops-tenant", "operator", "Operator");
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final AuthContextProvider auth = mock(AuthContextProvider.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final ChatFeedbackApplicationService feedbacks = mock(ChatFeedbackApplicationService.class);
    private final ChatRunApplicationService runs = mock(ChatRunApplicationService.class);
    private final IntentFeedbackHistoryService intentFeedbacks = mock(IntentFeedbackHistoryService.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ChatSession session;
    private ChatMessage message;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        configureSession("target-tenant", "target-user", "ACTIVE");
        when(auth.resolve()).thenReturn(operator);
        when(sessions.findById(anyString())).thenAnswer(invocation ->
                "session".equals(invocation.getArgument(0)) ? Optional.of(session) : Optional.empty());
        when(sessions.findByTenantIdAndUserIdAndId(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> session.tenantId().equals(invocation.getArgument(0))
                        && session.userId().equals(invocation.getArgument(1))
                        && session.id().equals(invocation.getArgument(2)) ? Optional.of(session) : Optional.empty());
        when(messages.pageMessages(any(ChatMessagePageQuery.class)))
                .thenAnswer(ignored -> new ChatMessagePage(List.of(message), "next-cursor"));
        when(messages.findVersionCandidatesByMessageIds(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(new ChatMessageVersionCandidate("assistant", "assistant", "assistant",
                        0, false, "NORMAL", null, null, Instant.EPOCH, "assistant"),
                        new ChatMessageVersionCandidate("assistant", "assistant-other", "assistant",
                                1, false, "NORMAL", null, "assistant", Instant.EPOCH, "assistant-other")));
        when(feedbacks.findActiveByMessages(any(), anyString(), any())).thenAnswer(ignored -> Map.of(
                "assistant", new ChatMessageFeedback("feedback", session.tenantId(), session.userId(),
                        "session", "assistant", "run-a", "LIKE", "ACTIVE", null, "owner feedback",
                        Map.of(), Instant.EPOCH, Instant.EPOCH)));
        when(runs.findOwnedRunsByIds(any(), any())).thenAnswer(ignored -> Map.of("run-a",
                new ChatRun("run-a", session.tenantId(), session.userId(), "session", ChatRunStatus.COMPLETED,
                        "DOMAIN_AGENT", "skill-a", "domain-agent", "downstream-session", 1L, 9L,
                        null, Instant.EPOCH, Instant.EPOCH, Map.of(), Instant.EPOCH, Instant.EPOCH)));
        when(intentFeedbacks.find(any(), anyString(), any())).thenAnswer(ignored -> Map.of("run-a",
                new IntentFeedback("intent-feedback", session.tenantId(), session.userId(), "session",
                        "run-a", "assistant", "entry", "CORRECT", null, null, "skill-a",
                        "intent-a", "Intent A", Instant.EPOCH)));
        var properties = new DomainAgentProperties();
        properties.setHistoryMobileCardFallbackEnabled(true);
        properties.setHistoryMobileCardUrls(Map.of("skill-a", MOBILE));
        var facade = new SessionApplicationService(sessions, messages, ids, permissions,
                null, null, null, null, null, null, mapper);
        var controller = new ChatSessionController(facade, feedbacks, runs, auth, permissions,
                new ChatMessageVersionViewAssembler(), new IntentFeedbackViewAssembler(intentFeedbacks),
                new HistoryMobileCardViewAssembler(properties, mapper));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({
            "ops-tenant,operator,ACTIVE", "ops-tenant,target-user,ACTIVE", "target-tenant,target-user,ACTIVE",
            "ops-tenant,operator,ARCHIVED", "ops-tenant,target-user,ARCHIVED", "target-tenant,target-user,ARCHIVED"
    })
    void operationsMatchesOwnerResponseAndOnlyAddsOneSessionLookup(String tenant, String user, String status)
            throws Exception {
        configureSession(tenant, user, status);
        when(auth.resolve()).thenReturn(new UserContext(tenant, user, user));
        JsonNode original = response(get(PATH), 200);
        verifyHistoryReads(false);
        clearReads();

        when(auth.resolve()).thenReturn(operator);
        JsonNode result = response(get(OPERATIONS).param("tenantId", "untrusted").param("userId", "untrusted"), 200);

        assertThat(result).isEqualTo(original);
        JsonNode assistant = result.path("items").get(0);
        assertThat(assistant.path("parts").get(0).path("payload").path("mobileCardUrl").asText()).isEqualTo(MOBILE);
        assertThat(assistant.path("assistantSource").asText()).isEqualTo("domain-agent");
        assertThat(assistant.path("feedback").path("commentText").asText()).isEqualTo("owner feedback");
        assertThat(assistant.path("intentFeedback").isObject()).isTrue();
        assertThat(assistant.path("versionInfo").isObject()).isTrue();
        assertThat(assistant.path("attachments").get(0).path("documentId").asText()).isEqualTo("doc");
        assertThat(message.parts().getFirst().payload()).doesNotContainKey("mobileCardUrl");
        assertThat(session.status()).isEqualTo(status);
        verifyHistoryReads(true);
        assertThat(auth.resolve()).isSameAs(operator);
    }

    @Test
    void originalMessagesStillRejectsForeignOwnerEvenWithTargetQueryParameters() throws Exception {
        JsonNode result = response(get(PATH).param("tenantId", session.tenantId())
                .param("userId", session.userId()), 200);
        assertThat(result.path("code").asText()).isEqualTo("ACCESS_DENIED");
        verify(sessions).findByTenantIdAndUserIdAndId(operator.tenantId(), operator.ownerUserId(), "session");
        verify(sessions).findById("session");
        verifyNoMoreInteractions(sessions);
        verifyNoInteractions(messages, feedbacks, runs, intentFeedbacks, ids);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETED", "MISSING"})
    void operationsRejectsDeletedOrMissingSessionBeforeReadingHistory(String state) throws Exception {
        if ("DELETED".equals(state)) {
            configureSession(session.tenantId(), session.userId(), state);
        } else {
            when(sessions.findById("session")).thenReturn(Optional.empty());
        }
        assertThat(response(get(OPERATIONS), 400).path("code").asText()).isEqualTo("BAD_REQUEST");
        verify(sessions).findById("session");
        verifyNoMoreInteractions(sessions);
        verifyNoInteractions(messages, feedbacks, runs, intentFeedbacks, ids);
    }

    @Test
    void operationsStillResolvesTheRequestIdentity() throws Exception {
        when(auth.resolve()).thenThrow(new SecurityException("当前用户 ID 缺失"));
        assertThat(response(get(OPERATIONS), 401).path("code").asText()).isEqualTo("AUTH_CONTEXT_MISSING");
        verifyNoInteractions(sessions, messages, feedbacks, runs, intentFeedbacks, ids);
    }

    @Test
    void selectedPathUsesOwnerScopeAndLeavesCurrentLeafUnchanged() throws Exception {
        when(messages.findByOwnerAndId(session.tenantId(), session.userId(), "assistant"))
                .thenReturn(Optional.of(message));
        response(get(OPERATIONS).param("leafMessageId", "assistant").param("cursor", "selected-cursor")
                .param("limit", "7"), 200);
        verify(messages).findByOwnerAndId(session.tenantId(), session.userId(), "assistant");
        verify(messages).pageMessages(new ChatMessagePageQuery(session.tenantId(), session.userId(),
                "session", "assistant", "selected-cursor", 7));
        verify(sessions).findById("session");
        verify(sessions, times(2)).findByTenantIdAndUserIdAndId(session.tenantId(), session.userId(), "session");
        verifyNoMoreInteractions(sessions);
        verifyNoInteractions(ids);
    }

    @Test
    void selectedLeafFromAnotherSessionIsRejected() throws Exception {
        ChatMessage foreign = new ChatMessage("foreign", session.tenantId(), session.userId(),
                "another-session", "assistant", "other", 1, Instant.EPOCH);
        when(messages.findByOwnerAndId(session.tenantId(), session.userId(), "foreign"))
                .thenReturn(Optional.of(foreign));
        response(get(OPERATIONS).param("leafMessageId", "foreign"), 400);
        verify(messages).findByOwnerAndId(session.tenantId(), session.userId(), "foreign");
        verifyNoMoreInteractions(messages);
        verifyNoInteractions(feedbacks, runs, intentFeedbacks, ids);
    }

    @ParameterizedTest
    @CsvSource({"0,51", "-1,51", "1,2", "200,201", "201,201"})
    void operationsUsesExistingPaginationLimitsAndHandlesEmptyHistory(int requested, int fetchLimit) throws Exception {
        ChatMessageMapper rows = mock(ChatMessageMapper.class);
        useStore(rows);
        JsonNode result = response(get(OPERATIONS).param("limit", String.valueOf(requested)), 200);
        assertThat(result.path("items").isEmpty()).isTrue();
        verify(rows).findActivePathPage(session.tenantId(), session.userId(), "session", null, null, fetchLimit);
        verifyNoMoreInteractions(rows);
        verify(sessions).findById("session");
        verify(sessions).findByTenantIdAndUserIdAndId(session.tenantId(), session.userId(), "session");
        verifyNoMoreInteractions(sessions);
        verifyNoInteractions(runs, intentFeedbacks, ids);
    }

    @Test
    void invalidAndCrossSessionCursorsAreRejectedByTheExistingCodec() throws Exception {
        ChatMessageMapper rows = mock(ChatMessageMapper.class);
        MyBatisChatMessageStore store = useStore(rows);
        when(rows.findActivePathPage(eq(session.tenantId()), eq(session.userId()), eq("other-session"),
                any(), any(), anyInt())).thenReturn(List.of(row("message-2", "other-session", 2),
                row("message-1", "other-session", 1)));
        String cursor = store.pageMessages(new ChatMessagePageQuery(
                session.tenantId(), session.userId(), "other-session", null, null, 1)).nextCursor();
        clearInvocations(rows);
        response(get(OPERATIONS).param("cursor", "invalid"), 400);
        response(get(OPERATIONS).param("cursor", cursor), 400);
        verifyNoInteractions(rows, feedbacks, runs, intentFeedbacks, ids);
    }

    private MyBatisChatMessageStore useStore(ChatMessageMapper rows) {
        var store = new MyBatisChatMessageStore(rows, mapper, new ChatStreamProperties());
        when(messages.pageMessages(any(ChatMessagePageQuery.class)))
                .thenAnswer(invocation -> store.pageMessages(invocation.getArgument(0)));
        return store;
    }

    private ChatMessageRow row(String id, String sessionId, int number) {
        var row = new ChatMessageRow();
        row.setId(id);
        row.setTenantId(session.tenantId());
        row.setUserId(session.userId());
        row.setSessionId(sessionId);
        row.setNodeOrder((long) number);
        row.setTreeDepth(number - 1);
        row.setSiblingIndex(0);
        row.setRole("user");
        row.setContent("question");
        row.setLocked(false);
        row.setCreatedAt(Instant.EPOCH);
        return row;
    }

    private void configureSession(String tenant, String user, String status) {
        session = new ChatSession("session", tenant, user, "title", status, "web", Instant.EPOCH, Instant.EPOCH);
        var card = new ChatMessagePart("part", tenant, user, "session", "assistant", "run-a",
                "CARD", "cardUrl", null, Map.of("source", "domain-agent", "cardUrl", "https://example.com/web.js"),
                1, Instant.EPOCH);
        message = new ChatMessage("assistant", tenant, user, "session", null, 2L, 1, 0, "assistant",
                "owner answer", 2, "run-a", "NORMAL", false, null, null, null, null,
                "{\"skillId\":\"skill-a\"}", List.of(card), Instant.EPOCH)
                .withAttachments(List.of(new ChatMessageAttachment("attachment", tenant, user, "session",
                        "assistant", "doc", 1, "file.pdf", "application/pdf", 10L, null, Instant.EPOCH)));
    }

    private void verifyHistoryReads(boolean operations) {
        if (operations) { verify(sessions).findById("session"); }
        verify(sessions, times(2)).findByTenantIdAndUserIdAndId(session.tenantId(), session.userId(), "session");
        verify(messages).pageMessages(new ChatMessagePageQuery(
                session.tenantId(), session.userId(), "session", null, null, 50));
        verify(messages).findVersionCandidatesByMessageIds(
                session.tenantId(), session.userId(), "session", List.of("assistant"));
        verify(feedbacks).findActiveByMessages(argThat(this::targetOwner), eq("session"), eq(List.of(message)));
        verify(runs).findOwnedRunsByIds(argThat(this::targetOwner), eq(List.of("run-a")));
        verify(intentFeedbacks).find(argThat(this::targetOwner), eq("session"), eq(java.util.Set.of("run-a")));
        verify(auth).resolve();
        verifyNoMoreInteractions(sessions, messages, feedbacks, runs, intentFeedbacks, auth);
        verifyNoInteractions(ids);
    }

    private boolean targetOwner(UserContext user) {
        return user != null && Objects.equals(session.tenantId(), user.tenantId())
                && Objects.equals(session.userId(), user.ownerUserId());
    }

    private void clearReads() {
        clearInvocations(sessions, messages, feedbacks, runs, intentFeedbacks, auth, permissions);
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
