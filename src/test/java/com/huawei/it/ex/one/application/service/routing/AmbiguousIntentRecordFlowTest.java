/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.config.IntentFailureStrategy;
import com.huawei.it.ex.one.application.config.IntentRecordProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentAgentRuntime;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.intent.IntentRecognitionRecord;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.routing.RoutingPolicy;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;
import com.huawei.it.ex.one.infrastructure.auth.NoopAuthHeaderProvider;
import com.huawei.it.ex.one.infrastructure.intent.DefaultIntentRetryPolicy;
import com.huawei.it.ex.one.infrastructure.intent.FinEurekaIntentService;
import com.huawei.it.ex.one.infrastructure.intent.FinEurekaIntentStreamClient;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceHttpProperties;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceRequestMapper;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceResponseMapper;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceWireMapper;
import com.huawei.it.ex.one.infrastructure.runtime.intentagent.BlockingIntentAgentRuntime;
import com.huawei.it.ex.one.infrastructure.runtime.intentagent.StreamingIntentAgentRuntime;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class AmbiguousIntentRecordFlowTest {
    private static final String RESPONSE = """
            {"code":200,"status":"success","auditOnly":"raw-response-only","optional":null,
             "data":{"result":{"routeAction":"CLARIFY","message":"choose",
               "clarification":{"type":"AMBIGUOUS_ROUTE","clarifyQuestion":"which?",
                 "candidateIntents":[{"intentId":"b","intentName":"B","accessName":"ex_skill-b","score":null},
                                     {"intentId":"a","intentName":"A","accessName":"ex_skill-a"}]}}}}
            """;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UserContext user = new UserContext("tenant", "user", "tester");
    private final ChatSession session = new ChatSession("session", "tenant", "user", "title", "ACTIVE", "web",
            Instant.EPOCH, Instant.EPOCH);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualBlockingAndStreamClientsRecordOncePerRecognitionWithoutPublicRawPayload(boolean streaming)
            throws Exception {
        Fixture fixture = fixture(streaming, true, Runnable::run);
        for (String trigger : List.of("first_turn", "clarify_answer", "domain_reject")) {
            List<RouteSignalFrame> frames = route(fixture, trigger, null);
            RouteSignalResult result = frames.stream().filter(RouteSignalFrame::resultFrame)
                    .map(RouteSignalFrame::result).findFirst().orElseThrow();
            assertThat(result.waitingIntentClarification()).isTrue();
            assertThat(json.writeValueAsString(frames))
                    .doesNotContain("auditRawResponse", "raw-response-only");
            IntentRecognitionRecord record = fixture.records().getLast();
            assertThat(record.runId()).isEqualTo("run-" + trigger);
            assertThat(record.queryText()).isEqualTo("actual intent query");
            assertThat(record.latencyMs()).isGreaterThanOrEqualTo(0L);
            assertThat(record.status()).isEqualTo("CLARIFY");
            assertThat(record.accepted()).isFalse();
            assertThat(record.candidateCount()).isEqualTo(2);
            assertThat(json.readTree(record.rawResponseJson())).isEqualTo(json.readTree(RESPONSE));
        }
        assertThat(fixture.httpCalls()).hasValue(3);
        assertThat(fixture.records()).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsBeforeClarificationLimitFallsBackToRelay(boolean streaming) {
        Fixture fixture = fixture(streaming, true, Runnable::run);
        RouteMemoryApplicationService memory = mock(RouteMemoryApplicationService.class);
        when(memory.maxClarificationRounds()).thenReturn(3);
        when(memory.activeClarificationCount(user, session.id())).thenReturn(3);
        List<RouteSignalFrame> frames = route(fixture, "clarify_answer", memory);
        RouteSignalResult result = frames.stream().filter(RouteSignalFrame::resultFrame)
                .map(RouteSignalFrame::result).findFirst().orElseThrow();
        assertThat(result.waitingIntentClarification()).isFalse();
        assertThat(result.route().type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(fixture.records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo("CLARIFY");
            assertThat(record.routeType()).isNull();
        });
        assertThat(fixture.httpCalls()).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledOrRejectedAuditDoesNotRetryOrDegradeIntent(boolean enabled) {
        AtomicInteger submissions = new AtomicInteger();
        Fixture fixture = fixture(true, enabled, task -> {
            submissions.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("full");
        });
        assertThat(route(fixture, "first_turn", null).stream().filter(RouteSignalFrame::resultFrame)
                .map(RouteSignalFrame::result).findFirst().orElseThrow().waitingIntentClarification()).isTrue();
        assertThat(submissions).hasValue(enabled ? 1 : 0);
        assertThat(fixture.httpCalls()).hasValue(1);
        assertThat(fixture.records()).isEmpty();
    }

    private List<RouteSignalFrame> route(Fixture fixture, String trigger, RouteMemoryApplicationService memory) {
        ChatCommand command = new ChatCommand("cmd", "tenant", "user", "session", null, "web", "user text",
                List.of(), Map.of(), null, null, ChatRunMode.NEXT, null, null, null, trigger);
        RouteSignalApplicationService routing = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"), fixture.runtime(), new RoutingPolicy(0.85),
                new RouteSignalProperties(false, true, IntentFailureStrategy.RELAY_FALLBACK), memory, null,
                fixture.recorder());
        return routing.routeInitialWithProgress(new RouteSignalRequest("run-" + trigger, user, session,
                command, List.of(), MemoryContext.empty(), "actual intent query", "msg-user"))
                .collectList().block(Duration.ofSeconds(5));
    }

    private Fixture fixture(boolean streaming, boolean enabled, java.util.concurrent.Executor executor) {
        IntentRecordProperties recordsConfig = new IntentRecordProperties();
        recordsConfig.setEnabled(enabled);
        List<IntentRecognitionRecord> records = new CopyOnWriteArrayList<>();
        AtomicInteger ids = new AtomicInteger();
        IntentRecognitionRecordService recorder = new IntentRecognitionRecordService(recordsConfig, records::add,
                (prefix, context) -> prefix + "_" + ids.incrementAndGet(), json, executor);
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setBaseUrl("http://intent.test");
        properties.setAccessName("intent-entry");
        properties.setResponseAccessNamePrefix("ex_");
        properties.setMaxRetries(0);
        IntentServiceWireMapper wire = new IntentServiceWireMapper(new IntentServiceRequestMapper(properties),
                new IntentServiceResponseMapper(json, properties, recordsConfig));
        AtomicInteger httpCalls = new AtomicInteger();
        WebClient.Builder web = WebClient.builder().exchangeFunction(request -> {
            httpCalls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", streaming ? "text/event-stream" : "application/json")
                    .body(streaming ? "event: result\ndata: " + RESPONSE.replace("\n", "") + "\n\n" : RESPONSE)
                    .build());
        });
        AuthHeaderProviderRegistry auth = new AuthHeaderProviderRegistry(
                new IntegrationAuthProperties(), List.of(new NoopAuthHeaderProvider()));
        IntentAgentRuntime runtime = streaming
                ? new StreamingIntentAgentRuntime(new FinEurekaIntentStreamClient(web, json, properties, wire, auth,
                        new DefaultIntentRetryPolicy(), Schedulers.boundedElastic()))
                : new BlockingIntentAgentRuntime(new FinEurekaIntentService(web, properties, wire, auth,
                        new DefaultIntentRetryPolicy()));
        return new Fixture(runtime, recorder, records, httpCalls);
    }

    private record Fixture(IntentAgentRuntime runtime, IntentRecognitionRecordService recorder,
                           List<IntentRecognitionRecord> records, AtomicInteger httpCalls) { }
}
