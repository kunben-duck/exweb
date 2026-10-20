/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.facade.ChatSessionFacade;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.service.chat.ChatFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessageVersionCandidate;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.interfaces.ApiExceptionHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ChatSessionMobileCardHistoryTest {
    private static final UserContext USER = new UserContext("tenant", "user", "tester");
    private static final String MOBILE = "https://example.com/mobile/card.js";
    private static final String METADATA = "{\"skillId\":\"skill-a\",\"runDurationMs\":12580}";
    private static final String BASE_PATH = "/v1/chat/sessions/session/messages";
    private final ChatSessionFacade facade = mock(ChatSessionFacade.class);
    private final ChatFeedbackApplicationService feedbacks = mock(ChatFeedbackApplicationService.class);
    private final ChatRunApplicationService runs = mock(ChatRunApplicationService.class);
    private final AuthContextProvider auth = mock(AuthContextProvider.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DomainAgentProperties properties = new DomainAgentProperties();
    private ChatMessage message;

    @BeforeEach
    void setup() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "cardUrl");
        payload.put("cardType", "url");
        payload.put("cardSources", List.of("cardUrl"));
        payload.put("cardUrl", "https://arbitrary-web.example/card.js");
        payload.put("optionalDetail", null);
        ChatMessagePart part = new ChatMessagePart("part", "tenant", "user", "session", "assistant",
                "run-a", "CARD", "cardUrl", "card summary", payload, 1, Instant.EPOCH);
        message = new ChatMessage("assistant", "tenant", "user", "session", null, 2L, 1, 0,
                "assistant", "original answer", 2, "run-a", "NORMAL", false,
                null, null, null, null, METADATA, List.of(part), Instant.EPOCH);
        ChatSession session = new ChatSession("session", "tenant", "user", "original title", "ACTIVE",
                "web", Instant.EPOCH, Instant.EPOCH);
        when(auth.resolve()).thenReturn(USER);
        when(facade.listMessages(USER, "session", null, null, 50))
                .thenReturn(new ChatMessagePage(List.of(message), "cursor-original"));
        when(facade.listMessageVersionCandidates(USER, "session", List.of("assistant"))).thenReturn(List.of(
                new ChatMessageVersionCandidate("assistant", "assistant", "assistant", 0, false,
                        "NORMAL", null, null, Instant.EPOCH, "assistant")));
        when(facade.listMessageTree(USER, "session")).thenReturn(List.of(message));
        when(facade.listVariants(USER, "session", "assistant")).thenReturn(List.of(message));
        when(facade.getSession(USER, "session")).thenReturn(session);
        when(feedbacks.findActiveByMessages(USER, "session", List.of(message))).thenReturn(Map.of());
        when(runs.findOwnedRunsByIds(USER, List.of("run-a"))).thenReturn(Map.of());
        properties.setHistoryMobileCardUrls(Map.of("skill-a", MOBILE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "tree", "variants"})
    void eachHistoryEntryAddsOnlyTheMobileFieldWithoutChangingQueriesOrStoredMessage(String entry) throws Exception {
        JsonNode original = request(entry, null);
        properties.setHistoryMobileCardFallbackEnabled(true);
        JsonNode enriched = request(entry, new HistoryMobileCardViewAssembler(properties, mapper));
        JsonNode payload = messageNode(entry, enriched).path("parts").get(0).path("payload");
        assertThat(payload.path("mobileCardUrl").asText()).isEqualTo(MOBILE);
        assertThat(payload.has("optionalDetail")).isTrue();
        assertThat(payload.get("optionalDetail").isNull()).isTrue();
        ((ObjectNode) payload).remove("mobileCardUrl");
        assertThat(enriched).isEqualTo(original);
        assertThat(message.metadataJson()).isEqualTo(METADATA);
        assertThat(message.content()).isEqualTo("original answer");
        assertThat(message.parts().getFirst().payload()).doesNotContainKey("mobileCardUrl");
        assertThat(message.parts().getFirst().createdAt()).isEqualTo(Instant.EPOCH);

        properties.setHistoryMobileCardFallbackEnabled(false);
        assertThat(request(entry, new HistoryMobileCardViewAssembler(properties, mapper))).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource({"page,false", "tree,false", "variants,false", "page,true", "tree,true", "variants,true"})
    void disabledAndEmptyMappingProduceIdenticalHttpResponses(String entry, boolean enabled) throws Exception {
        JsonNode original = request(entry, null);
        properties.setHistoryMobileCardFallbackEnabled(enabled);
        if (enabled) {
            properties.setHistoryMobileCardUrls(Map.of());
        }
        assertThat(request(entry, new HistoryMobileCardViewAssembler(properties, mapper))).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "tree", "variants"})
    void fallbackFailureCannotChangeSuccessfulHistoryHttpResponse(String entry) throws Exception {
        JsonNode original = request(entry, null);
        DomainAgentProperties failing = mock(DomainAgentProperties.class);
        when(failing.isHistoryMobileCardFallbackEnabled()).thenReturn(true);
        when(failing.getHistoryMobileCardUrls()).thenThrow(new IllegalStateException("injected"));
        assertThat(request(entry, new HistoryMobileCardViewAssembler(failing, mapper))).isEqualTo(original);
    }

    private JsonNode request(String entry, HistoryMobileCardViewAssembler overlay) throws Exception {
        clearInvocations(facade, feedbacks, runs, auth, permissions);
        ChatSessionController controller = overlay == null
                ? new ChatSessionController(facade, feedbacks, runs, auth, permissions,
                        new ChatMessageVersionViewAssembler())
                : new ChatSessionController(facade, feedbacks, runs, auth, permissions,
                        new ChatMessageVersionViewAssembler(), null, overlay);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String path = switch (entry) {
            case "tree" -> BASE_PATH + "/tree";
            case "variants" -> BASE_PATH + "/assistant/variants";
            default -> BASE_PATH;
        };
        MvcResult started = mvc.perform(get(path)).andReturn();
        MvcResult completed = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        verify(auth).resolve();
        verify(permissions).checkChatPermission(USER);
        verify(feedbacks).findActiveByMessages(USER, "session", List.of(message));
        verify(runs).findOwnedRunsByIds(USER, List.of("run-a"));
        switch (entry) {
            case "tree" -> {
                verify(facade).getSession(USER, "session");
                verify(facade).listMessageTree(USER, "session");
            }
            case "variants" -> verify(facade).listVariants(USER, "session", "assistant");
            default -> {
                verify(facade).listMessages(USER, "session", null, null, 50);
                verify(facade).listMessageVersionCandidates(USER, "session", List.of("assistant"));
            }
        }
        verifyNoMoreInteractions(facade, feedbacks, runs, auth, permissions);
        return mapper.readTree(completed.getResponse().getContentAsByteArray());
    }

    private JsonNode messageNode(String entry, JsonNode response) {
        return switch (entry) {
            case "tree" -> response.path("mapping").path("assistant").path("message");
            case "variants" -> response.get(0);
            default -> response.path("items").get(0);
        };
    }
}
