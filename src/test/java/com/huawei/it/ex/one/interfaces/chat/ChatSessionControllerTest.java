/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.huawei.it.ex.one.application.facade.ChatSessionFacade;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.service.chat.ChatFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.interfaces.ApiExceptionHandler;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatSessionDto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

class ChatSessionControllerTest {
    private static final UserContext USER = new UserContext("tenant1", "user1", "tester");
    private static final String PATH = "/v1/chat/sessions/session1";
    private static final ChatSession SESSION = new ChatSession("session1", "tenant1", "user1",
            "服务端标题", "ACTIVE", "web", Instant.EPOCH, Instant.EPOCH);

    private final ChatSessionFacade facade = mock(ChatSessionFacade.class);
    private final AuthContextProvider auth = mock(AuthContextProvider.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(auth.resolve()).thenReturn(USER);
        mvc = MockMvcBuilders.standaloneSetup(new ChatSessionController(facade,
                        mock(ChatFeedbackApplicationService.class), mock(ChatRunApplicationService.class),
                        auth, permissions, new ChatMessageVersionViewAssembler()))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @MethodSource("renameRequests")
    void postAndPatchUseTheSameHandlerAndForwardTheOriginalTitle(String method, String body, String title)
            throws Exception {
        when(facade.renameSession(USER, "session1", title)).thenReturn(SESSION);

        MvcResult started = mvc.perform(request(HttpMethod.valueOf(method), PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertThat(started.getHandler()).isInstanceOf(HandlerMethod.class);
        assertThat(((HandlerMethod) started.getHandler()).getMethod().getName()).isEqualTo("update");
        assertThat(started.getRequest().isAsyncStarted()).isTrue();
        MvcResult completed = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn();
        ChatSessionDto response = objectMapper.readValue(completed.getResponse().getContentAsByteArray(), ChatSessionDto.class);
        assertThat(response.sessionId()).isEqualTo(SESSION.id());
        assertThat(response.title()).isEqualTo(SESSION.title());
        assertThat(response.tenantId()).isEqualTo(SESSION.tenantId());
        assertThat(response.userId()).isEqualTo(SESSION.userId());
        assertThat(response.status()).isEqualTo(SESSION.status());
        assertThat(response.createdAt()).isEqualTo(SESSION.createdAt());
        assertThat(response.updatedAt()).isEqualTo(SESSION.updatedAt());
        verify(permissions).checkChatPermission(USER);
        verify(facade).renameSession(USER, "session1", title);
        verifyNoMoreInteractions(facade);
    }

    private static Stream<Arguments> renameRequests() {
        return Stream.of("POST", "PATCH").flatMap(method -> Stream.of(
                Arguments.of(method, "{\"title\":\"新会话标题\"}", "新会话标题"),
                Arguments.of(method, "{\"title\":\" 新标题 \"}", " 新标题 "),
                Arguments.of(method, "{\"title\":\"\"}", ""),
                Arguments.of(method, "{\"title\":\"   \"}", "   "),
                Arguments.of(method, "{\"title\":null}", null),
                Arguments.of(method, "{}", null),
                Arguments.of(method, "", null)));
    }

    @Test
    void openApiKeepsPatchCompatibilityAndIdenticalPostContract() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<?, ?> document = new Yaml(new SafeConstructor(options)).load(
                Files.readString(Path.of("docs/openapi/financeex-chatservice-v1.yaml")));
        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        Map<?, ?> sessionPath = (Map<?, ?>) paths.get("/v1/chat/sessions/{sessionId}");
        Map<?, ?> post = (Map<?, ?>) sessionPath.get("post");
        Map<?, ?> patch = (Map<?, ?>) sessionPath.get("patch");

        assertThat(post.get("operationId")).isEqualTo("renameChatSession");
        assertThat(post.get("deprecated")).isNotEqualTo(true);
        assertThat(patch.get("operationId")).isEqualTo("updateChatSession");
        assertThat(patch.get("deprecated")).isEqualTo(true);
        assertThat(post.get("parameters")).isEqualTo(patch.get("parameters"));
        assertThat(post.get("requestBody")).isEqualTo(patch.get("requestBody"));
        assertThat(post.get("responses")).isEqualTo(patch.get("responses"));
        assertThat(((Map<?, ?>) paths.get("/v1/documents/{documentId}")).get("patch")).isNotNull();
        assertThat(paths.values().stream().map(value -> (Map<?, ?>) value)
                .flatMap(path -> path.values().stream()).filter(Map.class::isInstance)
                .map(value -> ((Map<?, ?>) value).get("operationId")).filter(java.util.Objects::nonNull)
                .toList()).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void malformedJsonDoesNotCallTheFacade(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(facade, auth, permissions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void missingIdentityKeepsTheUnauthorizedResponse(String method) throws Exception {
        when(auth.resolve()).thenThrow(new SecurityException("身份上下文缺失"));
        mvc.perform(request(HttpMethod.valueOf(method), PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_CONTEXT_MISSING"));
        verifyNoInteractions(facade, permissions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void permissionFailureDoesNotInvokeTheRenameService(String method) throws Exception {
        doThrow(new SecurityException("无会话操作权限")).when(permissions).checkChatPermission(USER);
        mvc.perform(request(HttpMethod.valueOf(method), PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(facade);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void ownershipFailureKeepsTheExistingBusinessError(String method) throws Exception {
        when(facade.renameSession(USER, "session1", "新标题"))
                .thenThrow(new SecurityException("会话不属于当前用户"));
        MvcResult started = mvc.perform(request(HttpMethod.valueOf(method), PATH)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"新标题\"}")).andReturn();

        mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verify(facade).renameSession(USER, "session1", "新标题");
        verifyNoMoreInteractions(facade);
    }

    @ParameterizedTest
    @CsvSource({
            "POST,/v1/chat/sessions,create",
            "GET,/v1/chat/sessions/session1,get",
            "DELETE,/v1/chat/sessions/session1,delete",
            "POST,/v1/chat/sessions/session1/archive,archive",
            "POST,/v1/chat/sessions/session1/restore,restore"
    })
    void adjacentSessionMappingsRemainUnchanged(String method, String path, String handler) throws Exception {
        when(facade.createSession(USER, null, null, null, null)).thenReturn(SESSION);
        when(facade.getSession(USER, "session1")).thenReturn(SESSION);
        when(facade.deleteSession(USER, "session1")).thenReturn(SESSION);
        when(facade.archiveSession(USER, "session1")).thenReturn(SESSION);
        when(facade.restoreSession(USER, "session1")).thenReturn(SESSION);

        MvcResult started = mvc.perform(request(HttpMethod.valueOf(method), path)).andReturn();

        assertThat(((HandlerMethod) started.getHandler()).getMethod().getName()).isEqualTo(handler);
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session1"));
        assertThat(org.mockito.Mockito.mockingDetails(facade).getInvocations()).singleElement()
                .satisfies(invocation -> assertThat(invocation.getMethod().getName()).isNotEqualTo("renameSession"));
    }
}
