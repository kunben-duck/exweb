/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.huawei.it.ex.one.application.config.IntentFeedbackExecutorConfiguration;
import com.huawei.it.ex.one.application.config.IntentFeedbackProperties;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackException;
import com.huawei.it.ex.one.application.service.routing.IntentFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentFeedbackCommitService;
import com.huawei.it.ex.one.application.service.routing.IntentFeedbackTaskDispatcher;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;
import com.huawei.it.ex.one.interfaces.ApiExceptionHandler;
import com.huawei.it.ex.one.interfaces.ReactiveApiExceptionHandler;
import com.huawei.it.ex.one.interfaces.chat.dto.IntentFeedbackRequest;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class IntentFeedbackControllerTest {
    @Test
    @SuppressWarnings("unchecked")
    void openApiParsesWithoutDuplicateKeysAndAllLocalReferencesResolve() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<String, Object> document = new Yaml(new SafeConstructor(options)).load(
                Files.readString(Path.of("docs/openapi/financeex-chatservice-v1.yaml")));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertThat(paths).containsKey("/v1/chat/runs/{runId}/intent-feedback");
        assertThat((Map<String, Object>) paths.get("/v1/chat/runs/{runId}/intent-feedback"))
                .containsKeys("post", "get");
        verifyReferences(document, document);
    }

    private void verifyReferences(Object value, Map<String, Object> document) {
        if (value instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref instanceof String text && text.startsWith("#/")) {
                Object resolved = document;
                for (String key : text.substring(2).split("/")) {
                    assertThat(resolved).isInstanceOf(Map.class);
                    resolved = ((Map<?, ?>) resolved).get(key.replace("~1", "/").replace("~0", "~"));
                }
                assertThat(resolved).as(text).isNotNull();
            }
            map.values().forEach(child -> verifyReferences(child, document));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> verifyReferences(child, document));
        }
    }

    private static final UserContext USER = new UserContext("t", "u", "u");
    private final IntentFeedbackApplicationService service = mock(IntentFeedbackApplicationService.class);
    private final AuthContextProvider auth = mock(AuthContextProvider.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(auth.resolve()).thenReturn(USER);
        mvc = MockMvcBuilders.standaloneSetup(new IntentFeedbackController(service, auth, permissions))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void postReturnsFeedbackAndGetWithoutFeedbackReturns204() throws Exception {
        IntentFeedback saved = new IntentFeedback("id", "t", "u", "s", "a", "q", "fin",
                "INCORRECT_COMMENT", "wrong", null, null, null, null, Instant.now());
        when(service.record(eq(USER), eq("a"), any())).thenReturn(Mono.just(saved));
        MvcResult result = mvc.perform(post("/v1/chat/runs/a/intent-feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"feedbackType\":\"INCORRECT_COMMENT\",\"commentText\":\" wrong \",\"intentAccessName\":\"fin\"}"))
                .andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("a"))
                .andExpect(jsonPath("$.commentText").value("wrong"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
        when(service.find(USER, "a")).thenReturn(Mono.just(Optional.empty()));
        MvcResult get = mvc.perform(get("/v1/chat/runs/a/intent-feedback")).andReturn();
        mvc.perform(asyncDispatch(get)).andExpect(status().isNoContent());
        verify(permissions, org.mockito.Mockito.times(2)).checkChatPermission(USER);
    }

    @Test
    void invalidFieldCombinationReturns400BeforeService() throws Exception {
        mvc.perform(post("/v1/chat/runs/a/intent-feedback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedbackType\":\"INCORRECT_COMMENT\",\"commentText\":\"bad\",\"skillId\":\"b\"}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void realQueuedGetAndPostReturn503WithoutEnteringTransaction() throws Exception {
        IntentFeedbackProperties settings = new IntentFeedbackProperties();
        ThreadPoolTaskExecutor executor = new IntentFeedbackExecutorConfiguration().intentFeedbackExecutor(settings);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IntentFeedbackCommitService commits = mock(IntentFeedbackCommitService.class);
        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            IntentFeedbackApplicationService realService = new IntentFeedbackApplicationService(commits,
                    new IntentFeedbackTaskDispatcher(executor, settings));
            MockMvc queuedMvc = MockMvcBuilders.standaloneSetup(new IntentFeedbackController(realService, auth, permissions))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            MvcResult getResult = queuedMvc.perform(get("/v1/chat/runs/a/intent-feedback")).andReturn();
            MvcResult postResult = queuedMvc.perform(post("/v1/chat/runs/a/intent-feedback")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"feedbackType\":\"CORRECT\"}")).andReturn();
            for (MvcResult result : List.of(getResult, postResult)) {
                result.getAsyncResult(2000);
                queuedMvc.perform(asyncDispatch(result)).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("INTENT_FEEDBACK_UNAVAILABLE"));
            }
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            release.countDown();
            executor.getThreadPoolExecutor().submit(() -> {}).get(2, TimeUnit.SECONDS);
            verifyNoInteractions(commits);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.getThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void servletAndReactiveErrorsUseSameCodes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/v1/chat/runs/a/intent-feedback");
        var conflict = new ApiExceptionHandler().handleIntentFeedback(IntentFeedbackException.conflict(), request);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().code()).isEqualTo("INTENT_FEEDBACK_ALREADY_SUBMITTED");
        var unavailable = new ReactiveApiExceptionHandler().handleIntentFeedback(
                IntentFeedbackException.unavailable(new IllegalStateException("db")),
                MockServerWebExchange.from(MockServerHttpRequest.get(request.getRequestURI()).build()));
        assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
        assertThat(unavailable.getBody().code()).isEqualTo("INTENT_FEEDBACK_UNAVAILABLE");
    }
}
