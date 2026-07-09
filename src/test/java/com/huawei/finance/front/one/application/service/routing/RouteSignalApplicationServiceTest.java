package com.huawei.finance.front.one.application.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.RouteSignalProperties;
import com.huawei.finance.front.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseLibraryClient;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.routing.RoutingPolicy;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;
import com.huawei.finance.front.one.infrastructure.runtime.intentagent.BlockingIntentAgentRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
class RouteSignalApplicationServiceTest {
    private final UserContext user = new UserContext("tenant1", "user1", "tester");
    private final ChatSession session = new ChatSession("session1", "tenant1", "user1",
            "测试会话", "ACTIVE", "web", Instant.now(), Instant.now());
    private final ChatCommand command = new ChatCommand("cmd1", "tenant1", "user1", "session1",
            null, "web", "帮我报销一张发票", List.of(), Map.of());
    private final MemoryContext memory = MemoryContext.empty();

    @Test
    void disabledSignalsRouteInitialToRuntimeWithoutCallingClients() {
        AtomicInteger useCaseCalls = new AtomicInteger();
        AtomicInteger intentCalls = new AtomicInteger();
        RouteSignalApplicationService service = service(false, false,
                request -> {
                    useCaseCalls.incrementAndGet();
                    throw new AssertionError("use case client should not be called");
                },
                (command, memory, user) -> {
                    intentCalls.incrementAndGet();
                    throw new AssertionError("intent service should not be called");
                });

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(result.intentDecision()).isNull();
        assertThat(useCaseCalls).hasValue(0);
        assertThat(intentCalls).hasValue(0);
    }

    @Test
    void enabledUseCaseHitRoutesToDomainAgentAndSkipsIntent() {
        AtomicInteger intentCalls = new AtomicInteger();
        RouteSignalApplicationService service = service(true, true,
                request -> new UseCaseMatchResult(true, 0.91, "employee_reimbursement_agent",
                        "hit", Map.of(), Map.of()),
                (command, memory, user) -> {
                    intentCalls.incrementAndGet();
                    return complexIntent(0.95);
                });

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(result.route().selectedAgentCode()).isEqualTo("employee_reimbursement_agent");
        assertThat(result.intentDecision()).isNull();
        assertThat(intentCalls).hasValue(0);
    }

    @Test
    void useCaseMissAndIntentDisabledRoutesToRuntime() {
        RouteSignalApplicationService service = service(true, false,
                request -> UseCaseMatchResult.notMatched("no case"),
                (command, memory, user) -> simpleDomainAgentIntent());

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(result.intentDecision()).isNull();
    }

    @Test
    void intentEnabledSimpleTaskRoutesToDomainAgentWhenUseCaseDisabled() {
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> simpleDomainAgentIntent());

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(result.route().selectedAgentCode()).isEqualTo("employee_reimbursement_agent");
        assertThat(result.intentDecision()).isNotNull();
    }

    @Test
    void routeInitialWithProgressEmitsIntentAgentEventsBeforeRouteResult() {
        AtomicInteger intentCalls = new AtomicInteger();
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> {
                    intentCalls.incrementAndGet();
                    return simpleDomainAgentIntent();
                });

        StepVerifier.create(service.routeInitialWithProgress(new RouteSignalRequest(
                        "run1", user, session, command, List.of(), memory)), 1)
                .assertNext(frame -> {
                    assertThat(frame.eventFrame()).isTrue();
                    assertThat(frame.event().type()).isEqualTo("runtime.progress");
                    assertThat(frame.event().payload())
                            .containsEntry("source", "intent-agent")
                            .containsEntry("sourceType", "intent-start")
                            .containsEntry("stage", "intent_calling")
                            .containsEntry("routeTrigger", "first_turn");
                })
                .thenRequest(1)
                .assertNext(frame -> {
                    assertThat(frame.eventFrame()).isTrue();
                    assertThat(frame.event().type()).isEqualTo("runtime.progress");
                    assertThat(frame.event().payload())
                            .containsEntry("source", "intent-agent")
                            .containsEntry("sourceType", "intent-result")
                            .containsEntry("routeAction", "ROUTE_SINGLE")
                            .containsEntry("targetProvider", "domain-agent")
                            .containsEntry("targetId", "employee_reimbursement_agent");
                })
                .thenRequest(1)
                .assertNext(frame -> {
                    assertThat(frame.resultFrame()).isTrue();
                    assertThat(frame.result().route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
                    assertThat(frame.result().route().selectedAgentCode()).isEqualTo("employee_reimbursement_agent");
                })
                .verifyComplete();
        assertThat(intentCalls).hasValue(1);
    }

    @Test
    void intentWaitingClarificationStopsAtWaitingRoute() {
        IntentService intentService = new IntentService() {
            @Override
            public IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user) {
                throw new AssertionError("recognizeForRouting should be used");
            }

            @Override
            public IntentRecognitionResult recognizeForRouting(ChatCommand command, MemoryContext memory, UserContext user) {
                return IntentRecognitionResult.waitingClarification(Map.of(
                        "question", "请选择报销类型",
                        "options", List.of("差旅", "采购")
                ), "intent-session-1", "intent-request-1");
            }
        };
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentService);

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.waitingIntentClarification()).isTrue();
        assertThat(result.route().type()).isEqualTo(RouteType.SYSTEM_RESPONSE);
        assertThat(result.intentClarificationPayload())
                .containsEntry("source", "intent-agent")
                .containsEntry("sourceType", "intent-clarification-request")
                .containsEntry("interactionType", "INTENT_CLARIFICATION")
                .containsEntry("intentSessionId", "intent-session-1")
                .containsEntry("intentRequestId", "intent-request-1");
    }

    @Test
    void clarifyAnswerUsesInlineHistoryWhenRouteMemoryIsUnavailable() {
        AtomicReference<MemoryContext> capturedMemory = new AtomicReference<>();
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> {
                    capturedMemory.set(memory);
                    return simpleDomainAgentIntent();
                });
        ChatCommand clarifyAnswer = new ChatCommand("cmd2", "tenant1", "user1", "session1",
                null, "web", "处理方案", List.of(), Map.of(
                "routeTrigger", "clarify_answer",
                "intentClarification", Map.of(
                        "originalQuery", "再帮我看下方案",
                        "clarificationHistory", List.of(Map.of(
                                "type", "clarify",
                                "query", "再帮我看下方案",
                                "clarifyQuestion", "你想看处理方案还是项目方案？",
                                "clarificationType", "AMBIGUOUS_ROUTE",
                                "answer", "处理方案"
                        ))
                )
        ));

        RouteSignalResult result = service.routeInitial(user, session, clarifyAnswer, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(capturedMemory.get().routeMemory().routeTrigger()).isEqualTo("clarify_answer");
        assertThat(capturedMemory.get().routeMemory().history()).singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("type", "clarify")
                        .containsEntry("query", "再帮我看下方案")
                        .containsEntry("clarifyQuestion", "你想看处理方案还是项目方案？")
                        .containsEntry("answer", "处理方案"));
    }

    @Test
    void followUpClarificationKeepsOriginalQueryAndPreviousHistory() {
        IntentService intentService = new IntentService() {
            @Override
            public IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user) {
                throw new AssertionError("recognizeForRouting should be used");
            }

            @Override
            public IntentRecognitionResult recognizeForRouting(ChatCommand command, MemoryContext memory, UserContext user) {
                return IntentRecognitionResult.waitingClarification(Map.of(
                        "clarifyQuestion", "你关注哪个区域？",
                        "type", "UNCLEAR_REFERENCE"
                ), "intent-session-2", "intent-request-2");
            }
        };
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentService);
        ChatCommand clarifyAnswer = new ChatCommand("cmd2", "tenant1", "user1", "session1",
                null, "web", "处理方案", List.of(), Map.of(
                "routeTrigger", "clarify_answer",
                "intentClarification", Map.of(
                        "originalQuery", "再帮我看下方案",
                        "clarificationHistory", List.of(Map.of(
                                "type", "clarify",
                                "query", "再帮我看下方案",
                                "clarifyQuestion", "你想看处理方案还是项目方案？",
                                "answer", "处理方案"
                        ))
                )
        ));

        RouteSignalResult result = service.routeInitial(user, session, clarifyAnswer, List.of(), memory);

        assertThat(result.waitingIntentClarification()).isTrue();
        assertThat(result.intentClarificationPayload())
                .containsEntry("originalQuery", "再帮我看下方案")
                .containsEntry("clarifyTriggerQuery", "处理方案");
        assertThat((List<?>) result.intentClarificationPayload().get("clarificationHistory"))
                .singleElement()
                .satisfies(item -> assertThat((Map<String, Object>) item)
                        .containsEntry("query", "再帮我看下方案")
                        .containsEntry("answer", "处理方案"));
    }

    @Test
    void externalSignalFailuresDoNotBlockRuntimeFallback() {
        RouteSignalApplicationService service = service(true, true,
                request -> {
                    throw new IllegalStateException("use case down");
                },
                (command, memory, user) -> {
                    throw new IllegalStateException("intent down");
                });

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(result.intentDecision()).isNotNull();
        assertThat(result.intentDecision().intentCode()).isEqualTo("finance.runtime.degraded");
        assertThat(result.intentDecision().raw()).containsEntry("source", "intent-agent-degraded")
                .containsEntry("reason", "intent down");
    }

    private RouteSignalApplicationService service(boolean useCaseEnabled, boolean intentEnabled,
                                                  UseCaseLibraryClient useCaseLibraryClient,
                                                  IntentService intentService) {
        return new RouteSignalApplicationService(useCaseLibraryClient, new BlockingIntentAgentRuntime(intentService),
                new RoutingPolicy(0.85),
                new RouteSignalProperties(useCaseEnabled, intentEnabled));
    }

    private IntentDecision simpleDomainAgentIntent() {
        return new IntentDecision("employee.reimbursement", "员工报销", TaskComplexity.SIMPLE, 0.92,
                true, "employee_reimbursement_agent", Map.of(), List.of(), Map.of());
    }

    private IntentDecision complexIntent(double confidence) {
        return new IntentDecision("finance.complex", "复杂财经任务", TaskComplexity.COMPLEX, confidence,
                false, null, Map.of(), List.of(), Map.of());
    }
}
