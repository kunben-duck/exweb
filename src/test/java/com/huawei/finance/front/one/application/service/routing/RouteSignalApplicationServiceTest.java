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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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
                .containsEntry("sourceType", "intent-clarification-request")
                .containsEntry("waitingType", "INTENT_CLARIFICATION")
                .containsEntry("intentSessionId", "intent-session-1")
                .containsEntry("intentRequestId", "intent-request-1");
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
        assertThat(result.intentDecision().raw()).containsEntry("source", "route-signal-intent-degraded")
                .containsEntry("reason", "intent down");
    }

    private RouteSignalApplicationService service(boolean useCaseEnabled, boolean intentEnabled,
                                                  UseCaseLibraryClient useCaseLibraryClient,
                                                  IntentService intentService) {
        return new RouteSignalApplicationService(useCaseLibraryClient, intentService, new RoutingPolicy(0.85),
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
