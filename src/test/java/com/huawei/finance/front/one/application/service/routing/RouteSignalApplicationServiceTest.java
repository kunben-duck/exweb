package com.huawei.finance.front.one.application.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.IntentFailureStrategy;
import com.huawei.finance.front.one.application.config.RouteSignalProperties;
import com.huawei.finance.front.one.application.integration.agent.SelectedIntentContext;
import com.huawei.finance.front.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseLibraryClient;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseMatchRequest;
import com.huawei.finance.front.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.memory.RouteMemoryContext;
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
    void selectedIntentPresentationMetadataIsNotSentToUseCaseLibrary() {
        AtomicReference<UseCaseMatchRequest> capturedRequest = new AtomicReference<>();
        ChatCommand selectedCommand = new ChatCommand("cmd-selected", "tenant1", "user1", "session1",
                null, "web", "查询资金情况", List.of(), SelectedIntentContext.attach(
                Map.of("scene", "fund"), "fund_management", "资金管理"));
        RouteSignalApplicationService service = service(true, false,
                request -> {
                    capturedRequest.set(request);
                    return UseCaseMatchResult.notMatched("no case");
                },
                (command, memory, user) -> simpleDomainAgentIntent());

        RouteSignalResult result = service.routeInitial(user, session, selectedCommand, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().metadata()).containsExactlyEntriesOf(Map.of("scene", "fund"));
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
    void intentQueryOverridesOnlyIntentCommandMessage() {
        AtomicReference<ChatCommand> capturedIntentCommand = new AtomicReference<>();
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (intentCommand, intentMemory, routeUser) -> {
                    capturedIntentCommand.set(intentCommand);
                    return simpleDomainAgentIntent();
                });
        ChatCommand original = new ChatCommand("cmd-doc", "tenant1", "user1", "session1",
                null, "web", "帮我分析这个文档。",
                List.of(new AttachmentRef("doc1", "财务报表.pdf", "application/pdf", 1L)), Map.of());

        RouteSignalResult result = service.routeInitialWithProgress(new RouteSignalRequest(
                        "run-doc", user, session, original, original.attachments(), memory,
                        "帮我分析这个文档。 [用户上传文档] 财务报表.pdf"))
                .filter(RouteSignalFrame::resultFrame)
                .map(RouteSignalFrame::result)
                .blockLast();

        assertThat(result).isNotNull();
        assertThat(result.route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(capturedIntentCommand.get()).isNotNull();
        assertThat(capturedIntentCommand.get().message())
                .isEqualTo("帮我分析这个文档。 [用户上传文档] 财务报表.pdf");
        assertThat(original.message()).isEqualTo("帮我分析这个文档。");
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
                            .containsEntry("intentId", "employee.reimbursement")
                            .containsEntry("skillId", "employee_reimbursement_agent")
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
    void previousRelayRouteUsesFallbackFollowupTrigger() {
        AtomicReference<MemoryContext> capturedMemory = new AtomicReference<>();
        RouteMemoryApplicationService routeMemoryService = new RouteMemoryApplicationService(null, null, null) {
            @Override
            public boolean latestRouteIsRelayFallback(UserContext user, String sessionId) {
                return true;
            }

            @Override
            public RouteMemoryContext loadForIntent(UserContext user, String sessionId, String routeTrigger,
                                                    Map<String, Object> lastIntentRejectReason) {
                return new RouteMemoryContext(routeTrigger,
                        List.of(Map.of("type", "NO_MATCH", "query", "上一轮复杂问题", "intent", "")),
                        lastIntentRejectReason);
            }
        };
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> {
                    capturedMemory.set(memory);
                    return simpleDomainAgentIntent();
                },
                routeMemoryService);

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(capturedMemory.get().routeMemory().routeTrigger()).isEqualTo("fallback_followup");
        assertThat(capturedMemory.get().routeMemory().history()).containsExactly(
                Map.of("type", "NO_MATCH", "query", "上一轮复杂问题", "intent", ""));
    }

    @Test
    void sameRunInlineRouteIsMergedWithoutWaitingForRouteMemoryWrite() {
        Map<String, Object> appliedRoute = Map.of(
                "type", "route",
                "query", "原始财经问题",
                "intent", "资金助手");
        MemoryContext inlineMemory = memory.withRouteMemory(new RouteMemoryContext(
                "first_turn", List.of(appliedRoute), Map.of()));
        AtomicReference<MemoryContext> capturedMemory = new AtomicReference<>();
        RouteMemoryApplicationService routeMemoryService = new RouteMemoryApplicationService(null, null, null) {
            @Override
            public boolean latestRouteIsRelayFallback(UserContext user, String sessionId) {
                return false;
            }

            @Override
            public RouteMemoryContext loadForIntent(UserContext user, String sessionId, String routeTrigger,
                                                    Map<String, Object> lastIntentRejectReason) {
                return new RouteMemoryContext(routeTrigger, List.of(), lastIntentRejectReason);
            }
        };
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> {
                    capturedMemory.set(memory);
                    return simpleDomainAgentIntent();
                },
                routeMemoryService);

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), inlineMemory);

        assertThat(result.route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(capturedMemory.get().routeMemory().history()).containsExactly(appliedRoute);
    }

    @Test
    void sameRunInlineNoMatchIsMergedAsRouteHistory() {
        Map<String, Object> noMatch = Map.of(
                "type", "NO_MATCH",
                "query", "上一轮未命中的问题",
                "intent", "");
        MemoryContext inlineMemory = memory.withRouteMemory(new RouteMemoryContext(
                "fallback_followup", List.of(noMatch), Map.of()));
        AtomicReference<MemoryContext> capturedMemory = new AtomicReference<>();
        RouteMemoryApplicationService routeMemoryService = new RouteMemoryApplicationService(null, null, null) {
            @Override
            public boolean latestRouteIsRelayFallback(UserContext user, String sessionId) {
                return true;
            }

            @Override
            public RouteMemoryContext loadForIntent(UserContext user, String sessionId, String routeTrigger,
                                                    Map<String, Object> lastIntentRejectReason) {
                return new RouteMemoryContext(routeTrigger, List.of(), lastIntentRejectReason);
            }
        };
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> {
                    capturedMemory.set(memory);
                    return simpleDomainAgentIntent();
                },
                routeMemoryService);

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), inlineMemory);

        assertThat(result.route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(capturedMemory.get().routeMemory().history()).containsExactly(noMatch);
    }

    @Test
    void topLevelUserCorrectionTriggerWinsOverMetadataAndFallback() {
        AtomicReference<MemoryContext> capturedMemory = new AtomicReference<>();
        RouteMemoryApplicationService routeMemoryService = new RouteMemoryApplicationService(null, null, null) {
            @Override
            public boolean latestRouteIsRelayFallback(UserContext user, String sessionId) {
                return true;
            }

            @Override
            public RouteMemoryContext loadForIntent(UserContext user, String sessionId, String routeTrigger,
                                                    Map<String, Object> lastIntentRejectReason) {
                return new RouteMemoryContext(routeTrigger, List.of(), lastIntentRejectReason);
            }
        };
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> {
                    capturedMemory.set(memory);
                    return simpleDomainAgentIntent();
                },
                routeMemoryService);
        ChatCommand correction = new ChatCommand("cmd2", "tenant1", "user1", "session1",
                null, "web", "用户主动修正路由", List.of(), Map.of("routeTrigger", "first_turn"),
                null, null, com.huawei.finance.front.one.domain.chat.ChatRunMode.NEXT,
                null, null, null, "user_correction");

        RouteSignalResult result = service.routeInitial(user, session, correction, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(capturedMemory.get().routeMemory().routeTrigger()).isEqualTo("user_correction");
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
        assertThat(result.intentFailureStrategy()).isEqualTo(IntentFailureStrategy.RELAY_FALLBACK);
    }

    @Test
    void failRunStrategyReturnsFailureResultWithoutRuntimeRoute() {
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> {
                    throw new IllegalStateException("intent down");
                }, null, IntentFailureStrategy.FAIL_RUN);

        List<RouteSignalFrame> frames = service.routeInitialWithProgress(new RouteSignalRequest(
                "run1", user, session, command, List.of(), memory)).collectList().block();

        assertThat(frames).isNotNull();
        assertThat(frames)
                .filteredOn(frame -> frame.eventFrame()
                        && "intent-result".equals(frame.event().payload().get("sourceType")))
                .singleElement()
                .satisfies(frame -> assertThat(frame.event().payload())
                        .containsEntry("routeAction", "DEGRADED")
                        .containsEntry("failureStrategy", "FAIL_RUN")
                        .containsEntry("targetProvider", "none")
                        .containsEntry("suggestedAction", "SELECT_DOMAIN_AGENT"));
        RouteSignalResult result = frames.getLast().result();
        assertThat(result.failRunOnIntentFailure()).isTrue();
        assertThat(result.route()).isNull();
        assertThat(result.intentDecision().intentCode()).isEqualTo("finance.runtime.degraded");
    }

    @Test
    void failRunStrategyDoesNotTreatValidNoMatchAsFailure() {
        IntentDecision noMatch = new IntentDecision(
                "finance.runtime.no_intent", "未识别到可用意图", TaskComplexity.COMPLEX,
                0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
        RouteSignalApplicationService service = service(false, true,
                request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> noMatch,
                null,
                IntentFailureStrategy.FAIL_RUN);

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.route().type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(result.intentFailure()).isFalse();
        assertThat(result.failRunOnIntentFailure()).isFalse();
    }

    @Test
    void intentAgentStreamErrorAlsoUsesConfiguredFailureStrategy() {
        RouteSignalApplicationService service = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                request -> reactor.core.publisher.Flux.error(new IllegalStateException("stream down")),
                new RoutingPolicy(0.85),
                new RouteSignalProperties(false, true, IntentFailureStrategy.FAIL_RUN));

        RouteSignalResult result = service.routeInitial(user, session, command, List.of(), memory);

        assertThat(result.failRunOnIntentFailure()).isTrue();
        assertThat(result.intentDecision()).isNotNull();
        assertThat(result.intentDecision().intentCode()).isEqualTo("finance.runtime.degraded");
        assertThat(result.intentFailureReason()).contains("stream down");
    }

    private RouteSignalApplicationService service(boolean useCaseEnabled, boolean intentEnabled,
                                                  UseCaseLibraryClient useCaseLibraryClient,
                                                  IntentService intentService) {
        return service(useCaseEnabled, intentEnabled, useCaseLibraryClient, intentService, null);
    }

    private RouteSignalApplicationService service(boolean useCaseEnabled, boolean intentEnabled,
                                                  UseCaseLibraryClient useCaseLibraryClient,
                                                  IntentService intentService,
                                                  RouteMemoryApplicationService routeMemoryService) {
        return service(useCaseEnabled, intentEnabled, useCaseLibraryClient, intentService, routeMemoryService,
                IntentFailureStrategy.RELAY_FALLBACK);
    }

    private RouteSignalApplicationService service(boolean useCaseEnabled, boolean intentEnabled,
                                                  UseCaseLibraryClient useCaseLibraryClient,
                                                  IntentService intentService,
                                                  RouteMemoryApplicationService routeMemoryService,
                                                  IntentFailureStrategy failureStrategy) {
        return new RouteSignalApplicationService(useCaseLibraryClient, new BlockingIntentAgentRuntime(intentService),
                new RoutingPolicy(0.85),
                new RouteSignalProperties(useCaseEnabled, intentEnabled, failureStrategy), routeMemoryService);
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
