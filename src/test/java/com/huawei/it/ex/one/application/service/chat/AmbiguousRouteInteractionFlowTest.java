/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class AmbiguousRouteInteractionFlowTest extends ChatFlowTestSupport {
    @Test
    void firstAmbiguousResultPersistsWaitCardInteractionAndDeadline() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions =
                new InMemoryInteractionRequestRepository();
        RouteSignalApplicationService routeService =
                ambiguousWaitingRouteService();
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions,
                messages,
                runs,
                events,
                routeService,
                domainClient(new AtomicReference<>()),
                noopRuntime(),
                new CapturingRuntimeBindingRepository(),
                new DomainAgentProperties(),
                liveEventBus(),
                interactions);
        UserContext user = new UserContext("tenant-1", "user-1", "User One");

        List<ChatEvent> stream = service.executeRun(
                        user,
                        new ChatCommand(
                                "command-1",
                                null,
                                null,
                                null,
                                null,
                                "web",
                                "分析经营情况",
                                List.of(),
                                Map.of()),
                        RuntimeForwardHeaders.empty())
                .collectList()
                .block();

        assertThat(stream).extracting(ChatEvent::type)
                .containsExactly(
                        "run.started",
                        "runtime.card",
                        "message.completed",
                        "run.waiting_user");
        ChatEvent card = stream.stream()
                .filter(event -> "runtime.card".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(card.payload())
                .containsEntry("clarificationType", "AMBIGUOUS_ROUTE")
                .containsEntry("autoSelectTimeoutMs", 30_000L)
                .containsEntry("actions", List.of(
                        Map.of("type", "AUTO_SELECT", "displayName", "代为选择"),
                        Map.of("type", "OTHER", "displayName", "其他")));
        assertThat(card.payload().get("autoSelectAt")).isInstanceOf(String.class);
        ChatEvent waiting = stream.getLast();
        assertThat(waiting.payload())
                .containsEntry("interactionType", "INTENT_CLARIFICATION")
                .containsEntry("clarificationType", "AMBIGUOUS_ROUTE")
                .containsEntry("autoSelectAt", card.payload().get("autoSelectAt"))
                .containsEntry("autoSelectTimeoutMs", 30_000L);
        assertThat(interactions.requests.values()).singleElement().satisfies(interaction -> {
            assertThat(interaction.status()).isEqualTo(ChatInteractionStatus.WAITING);
            assertThat(interaction.requestPayload())
                    .containsEntry("autoSelectAt", card.payload().get("autoSelectAt"))
                    .containsEntry("autoSelectTimeoutMs", 30_000L);
        });
        assertThat(messages.messages).hasSize(2);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .satisfies(assistant -> assertThat(assistant.parts())
                        .extracting(part -> part.partType())
                        .contains("INTENT_CLARIFICATION_REQUEST"));
    }

    @Test
    void selectedCandidateSkipsIntentAndReusesOriginalAssistant() {
        Scenario scenario = scenario();
        RuntimeForwardHeaders headers =
                RuntimeForwardHeaders.fromCookieHeader("SESSION=selected", 128);
        ChatRunStartResult started = scenario.start(continueCommand(
                scenario.interaction().id(),
                "DOMAIN_AGENT",
                "skill-low",
                Map.of(),
                null,
                Map.of("language", "zh_CN")), headers);

        scenario.awaitCompleted();

        assertThat(scenario.routeCalls()).hasValue(0);
        assertThat(scenario.domainRequest().get()).satisfies(request -> {
            assertThat(request.domainAgentId()).isEqualTo("skill-low");
            assertThat(request.query()).isEqualTo("用户:分析经营情况");
            assertThat(request.messageId()).isEqualTo(scenario.interaction().userMessageId());
            assertThat(request.metadata()).containsEntry("language", "zh_CN");
            assertThat(request.forwardHeaders().cookieHeader()).isEqualTo("SESSION=selected");
        });
        assertThat(scenario.messages().messages).hasSize(2);
        ChatMessage assistant = scenario.assistant();
        assertThat(assistant.id()).isEqualTo("msg-assistant");
        assertThat(assistant.runId()).isEqualTo(started.runId());
        assertThat(assistant.content()).isEqualTo("skill-low 最终回答");
        assertThat(assistant.parts()).extracting(part -> part.partType())
                .contains("INTENT_CLARIFICATION_RESPONSE", "ANSWER");
        assertThat(scenario.responseEvent().payload())
                .containsEntry("interactionId", scenario.interaction().id())
                .containsEntry("assistantMessageId", "msg-assistant")
                .containsEntry("sourceRunId", "run-a")
                .containsEntry("selectionSource", "USER")
                .containsEntry("selectedSkillId", "skill-low");
        assertThat(scenario.bindings().saved.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(scenario.bindings().saved.metadata())
                .containsEntry("routeSource", "user-confirmed");
    }

    @Test
    void delegatedSelectionUsesHighestConfidenceCandidate() {
        Scenario scenario = scenario();

        scenario.start(continueCommand(
                scenario.interaction().id(),
                null,
                null,
                Map.of(),
                "AUTO_SELECT",
                Map.of()), RuntimeForwardHeaders.empty());
        scenario.awaitCompleted();

        assertThat(scenario.routeCalls()).hasValue(0);
        assertThat(scenario.domainRequest().get().domainAgentId()).isEqualTo("skill-high");
        assertThat(scenario.responseEvent().payload())
                .containsEntry("selectionSource", "DELEGATED")
                .containsEntry("interactionAction", "AUTO_SELECT")
                .containsEntry("selectedSkillId", "skill-high");
        assertThat(scenario.bindings().saved.metadata())
                .containsEntry("routeSource", "user-delegated-auto-selected");
    }

    @Test
    void otherAnswerRerunsIntentAndStillReusesOriginalAssistant() {
        Scenario scenario = scenario();

        scenario.start(continueCommand(
                scenario.interaction().id(),
                null,
                null,
                Map.of("请选择处理技能", "其他需求"),
                null,
                Map.of()), RuntimeForwardHeaders.empty());
        scenario.awaitCompleted();

        assertThat(scenario.routeCalls()).hasValue(1);
        assertThat(scenario.intentQuery()).hasValue("其他需求");
        assertThat(scenario.domainRequest().get()).satisfies(request -> {
            assertThat(request.domainAgentId()).isEqualTo("skill-from-intent");
            assertThat(request.query())
                    .isEqualTo("用户:分析经营情况；系统追问:请选择处理技能；用户:其他需求");
        });
        assertThat(scenario.messages().messages).hasSize(2);
        assertThat(scenario.assistant().id()).isEqualTo("msg-assistant");
        assertThat(scenario.responseEvent().payload())
                .containsEntry("selectionSource", "USER")
                .containsEntry("interactionAction", "OTHER")
                .containsEntry("answerText", "其他需求");
    }

    @Test
    void invalidCandidateFailsBeforeInteractionClaim() {
        Scenario scenario = scenario();
        ChatCommand command = continueCommand(
                scenario.interaction().id(),
                "DOMAIN_AGENT",
                "skill-not-offered",
                Map.of(),
                null,
                Map.of());

        StepVerifier.create(scenario.service().startRun(
                        scenario.user(), command, RuntimeForwardHeaders.empty()))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("不属于当前 AMBIGUOUS_ROUTE 候选技能"))
                .verify();

        assertThat(scenario.interactions().claimCalls).hasValue(0);
        assertThat(scenario.interactions().requests.get(scenario.interaction().id()).status())
                .isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(scenario.runs().runs).isEmpty();
        assertThat(scenario.domainRequest()).hasValue(null);
    }

    private Scenario scenario() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions =
                new InMemoryInteractionRequestRepository();
        CapturingRuntimeBindingRepository bindings =
                new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant-1", "user-1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicReference<String> intentQuery = new AtomicReference<>();
        AtomicReference<DomainAgentRequest> domainRequest = new AtomicReference<>();
        RouteSignalApplicationService routeService =
                routeService(routeCalls, intentQuery);
        DomainAgentClient domainClient = domainClient(domainRequest);
        AgentRuntime relayRuntime = noopRuntime();
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions,
                messages,
                runs,
                events,
                routeService,
                domainClient,
                relayRuntime,
                bindings,
                new DomainAgentProperties(),
                liveEventBus(),
                interactions);
        ChatInteractionRequest interaction =
                seedWaitingInteraction(sessions, messages, interactions, user);
        return new Scenario(
                service,
                user,
                messages,
                runs,
                events,
                interactions,
                bindings,
                interaction,
                routeCalls,
                intentQuery,
                domainRequest);
    }

    private RouteSignalApplicationService routeService(
            AtomicInteger routeCalls,
            AtomicReference<String> intentQuery) {
        return new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, user) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(
                    RouteSignalRequest request) {
                routeCalls.incrementAndGet();
                intentQuery.set(request.intentQuery());
                IntentDecision intent = new IntentDecision(
                        "intent-from-other",
                        "其他需求技能",
                        TaskComplexity.SIMPLE,
                        0.95,
                        true,
                        "skill-from-intent",
                        Map.of("routeAction", "ROUTE_SINGLE"),
                        List.of(),
                        Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.domainAgent(
                                "skill-from-intent",
                                "intent-agent",
                                0.95,
                                "intent matched"),
                        intent,
                        1L,
                        0.85)));
            }
        };
    }

    private RouteSignalApplicationService ambiguousWaitingRouteService() {
        return new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, user) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(
                    RouteSignalRequest request) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("routeAction", "CLARIFY");
                payload.put("type", "AMBIGUOUS_ROUTE");
                payload.put("clarificationType", "AMBIGUOUS_ROUTE");
                payload.put("originalQuery", request.intentQuery());
                payload.put("clarifyQuestion", "请选择处理技能");
                payload.put("candidateIntents", List.of(
                        candidate("intent-low", "技能低", "skill-low", 0.60),
                        candidate("intent-high", "技能高", "skill-high", 0.90)));
                return Flux.just(RouteSignalFrame.result(
                        RouteSignalResult.waitingIntentClarification(
                                payload,
                                10L,
                                0.85,
                                "intent-session-1",
                                "intent-request-1")));
            }
        };
    }

    private DomainAgentClient domainClient(
            AtomicReference<DomainAgentRequest> captured) {
        return new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                captured.set(request);
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(),
                        request.sessionId(),
                        request.domainAgentId() + " 最终回答"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
    }

    private ChatInteractionRequest seedWaitingInteraction(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryInteractionRequestRepository interactions,
            UserContext user) {
        Instant now = Instant.now();
        sessions.save(new ChatSession(
                "session-1",
                user.tenantId(),
                user.ownerUserId(),
                "测试会话",
                "ACTIVE",
                "web",
                "msg-user",
                "msg-assistant",
                null,
                null,
                1L,
                null,
                now,
                now));
        messages.save(new ChatMessage(
                "msg-user",
                user.tenantId(),
                user.ownerUserId(),
                "session-1",
                null,
                1L,
                0,
                1,
                "user",
                "分析经营情况",
                null,
                "run-a",
                "NORMAL",
                false,
                null,
                null,
                null,
                null,
                null,
                now));
        messages.save(new ChatMessage(
                "msg-assistant",
                user.tenantId(),
                user.ownerUserId(),
                "session-1",
                "msg-user",
                2L,
                1,
                1,
                "assistant",
                "请选择处理技能",
                null,
                "run-a",
                "NORMAL",
                false,
                null,
                null,
                null,
                null,
                "{\"finishReason\":\"WAITING_USER\"}",
                now));
        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("source", "intent-agent");
        requestPayload.put("sourceType", "intent-clarification-request");
        requestPayload.put("interactionType", "INTENT_CLARIFICATION");
        requestPayload.put("routeAction", "CLARIFY");
        requestPayload.put("clarificationType", "AMBIGUOUS_ROUTE");
        requestPayload.put("originalQuery", "分析经营情况");
        requestPayload.put("clarifyQuestion", "请选择处理技能");
        requestPayload.put("candidateIntents", List.of(
                candidate("intent-low", "技能低", "skill-low", 0.60),
                candidate("intent-high", "技能高", "skill-high", 0.90)));
        ChatInteractionRequest interaction = new ChatInteractionRequest(
                "interaction-1",
                user.tenantId(),
                user.ownerUserId(),
                "session-1",
                "run-a",
                null,
                "msg-user",
                "msg-assistant",
                "intent-agent",
                null,
                "intent-session-1",
                null,
                ChatInteractionType.INTENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                requestPayload,
                Map.of(),
                now.plus(Duration.ofHours(1)),
                null,
                null,
                now,
                now);
        interactions.insert(interaction);
        return interaction;
    }

    private Map<String, Object> candidate(
            String intentId,
            String intentName,
            String skillId,
            double confidence) {
        return Map.of(
                "intentId", intentId,
                "intentName", intentName,
                "skillId", skillId,
                "confidence", confidence);
    }

    private ChatCommand continueCommand(
            String interactionId,
            String targetType,
            String targetId,
            Map<String, Object> answers,
            String interactionAction,
            Map<String, Object> metadata) {
        return new ChatCommand(
                null,
                null,
                null,
                "session-1",
                null,
                "web",
                null,
                List.of(),
                metadata,
                targetType,
                targetId,
                ChatRunMode.CONTINUE_INTERACTION,
                null,
                null,
                null,
                null,
                interactionId,
                null,
                null,
                answers,
                null,
                null,
                null,
                interactionAction);
    }

    private final class Scenario {
        private final FinanceEXChatService service;
        private final UserContext user;
        private final InMemoryMessageRepository messages;
        private final InMemoryRunRepository runs;
        private final InMemoryEventStore events;
        private final InMemoryInteractionRequestRepository interactions;
        private final CapturingRuntimeBindingRepository bindings;
        private final ChatInteractionRequest interaction;
        private final AtomicInteger routeCalls;
        private final AtomicReference<String> intentQuery;
        private final AtomicReference<DomainAgentRequest> domainRequest;

        private Scenario(
                FinanceEXChatService service,
                UserContext user,
                InMemoryMessageRepository messages,
                InMemoryRunRepository runs,
                InMemoryEventStore events,
                InMemoryInteractionRequestRepository interactions,
                CapturingRuntimeBindingRepository bindings,
                ChatInteractionRequest interaction,
                AtomicInteger routeCalls,
                AtomicReference<String> intentQuery,
                AtomicReference<DomainAgentRequest> domainRequest) {
            this.service = service;
            this.user = user;
            this.messages = messages;
            this.runs = runs;
            this.events = events;
            this.interactions = interactions;
            this.bindings = bindings;
            this.interaction = interaction;
            this.routeCalls = routeCalls;
            this.intentQuery = intentQuery;
            this.domainRequest = domainRequest;
        }

        private ChatRunStartResult start(
                ChatCommand command,
                RuntimeForwardHeaders headers) {
            return service.startRun(user, command, headers).block();
        }

        private void awaitCompleted() {
            awaitEvent(events, "run.completed");
            awaitInteractionStatus(
                    interactions,
                    interaction.id(),
                    ChatInteractionStatus.ANSWERED);
        }

        private ChatMessage assistant() {
            return messages.findByOwnerAndId(
                            user.tenantId(),
                            user.ownerUserId(),
                            interaction.assistantMessageId())
                    .orElseThrow();
        }

        private ChatEvent responseEvent() {
            return events.events.stream()
                    .filter(event -> "runtime.card".equals(event.type()))
                    .filter(event -> "intent-clarification-response".equals(
                            event.payload().get("sourceType")))
                    .findFirst()
                    .orElseThrow();
        }

        private FinanceEXChatService service() {
            return service;
        }

        private UserContext user() {
            return user;
        }

        private InMemoryMessageRepository messages() {
            return messages;
        }

        private InMemoryRunRepository runs() {
            return runs;
        }

        private InMemoryInteractionRequestRepository interactions() {
            return interactions;
        }

        private CapturingRuntimeBindingRepository bindings() {
            return bindings;
        }

        private ChatInteractionRequest interaction() {
            return interaction;
        }

        private AtomicInteger routeCalls() {
            return routeCalls;
        }

        private AtomicReference<String> intentQuery() {
            return intentQuery;
        }

        private AtomicReference<DomainAgentRequest> domainRequest() {
            return domainRequest;
        }
    }
}
