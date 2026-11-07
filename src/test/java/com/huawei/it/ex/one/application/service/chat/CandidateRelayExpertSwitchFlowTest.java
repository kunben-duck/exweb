/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingCompletionPolicy;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.CandidateDomainAgentSwitchCommand;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.routing.RoutingPolicy;
import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;
import com.huawei.it.ex.one.infrastructure.runtime.relay.RelayAgentRuntime;
import com.huawei.it.ex.one.infrastructure.runtime.relay.RelayRuntimeProtocolAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class CandidateRelayExpertSwitchFlowTest extends ChatFlowTestSupport {
    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemoryMessageRepository messages = new InMemoryMessageRepository() {
        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenant, String owner, String messageId) {
            return super.findByOwnerAndId(tenant, owner, messageId)
                    .map(message -> message.withAttachments(findAttachments(tenant, owner, messageId)));
        }
    };
    private final InMemoryRunRepository runs = new InMemoryRunRepository();
    private final InMemoryEventStore events = new InMemoryEventStore() {
        @Override
        public ChatEvent append(ChatEvent event) {
            if (failSourceType != null && failSourceType.equals(event.payload().get("sourceType"))) {
                throw new IllegalStateException("test persistence failure");
            }
            return super.append(event);
        }
    };
    private final MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
    private final InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
    private final UserContext user = new UserContext("tenant1", "user1", "User One");
    private final List<AgentRuntimeRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger intentCalls = new AtomicInteger();
    private final AtomicInteger useCaseCalls = new AtomicInteger();
    private boolean nonSticky;
    private boolean questionnaire;
    private boolean withAttachments;
    private volatile String failSourceType;

    @Override
    RuntimeBindingCompletionPolicy bindingCompletionPolicy() {
        return new RuntimeBindingCompletionPolicy(nonSticky ? "analysis" : "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOMAIN_AGENT", "DOMAIN_EXPERT", "INTENT_EXPERT"})
    void candidateExpertBindsAndResumesAcrossNewServiceAndManualSelection(String sourceType) {
        FinanceEXChatService service = service(0);
        ChatRun source = execute(service, initial(sourceType));
        RuntimeBinding original = "DOMAIN_AGENT".equals(sourceType) ? null : relayBinding();
        int priorIntent = intentCalls.get();
        int priorUseCase = useCaseCalls.get();

        ChatRun switched = switchCandidate(service, source, "RE_analysis");
        RuntimeBinding binding = relayBinding();
        assertThat(messages.findByOwnerAndId("tenant1", "user1", switched.assistantMessageId()).orElseThrow().channel())
                .isEqualTo("mobile");
        assertThat(messages.findByOwnerAndId("tenant1", "user1", switched.userMessageId()).orElseThrow().channel())
                .isEqualTo("web");
        assertExpert(binding, switched);
        assertThat(requests.getLast().runtimeSessionMode()).isEqualTo(
                "DOMAIN_EXPERT".equals(sourceType) ? RuntimeSessionMode.RESUME : RuntimeSessionMode.NEW);
        if (original != null) {
            if ("DOMAIN_EXPERT".equals(sourceType)) {
                assertThat(binding.id()).isEqualTo(original.id());
            } else {
                assertThat(binding.id()).isNotEqualTo(original.id());
                assertThat(bindings.findById(original.id()).orElseThrow().status())
                        .isEqualTo(RuntimeBindingStatus.CANCELLED);
            }
        }
        assertThat(IntentExpertContext.fromSessionMetadata(
                sessions.sessions.get(source.sessionId()).metadataJson())).isEmpty();
        assertThat(events.events).filteredOn(event -> switched.id().equals(event.runId())
                && "candidate-skill-switch".equals(event.payload().get("sourceType")))
                .singleElement().satisfies(event -> assertThat(event.payload())
                        .containsEntry("skillId", "RE_analysis")
                        .containsEntry("targetType", "DOMAIN_EXPERT")
                        .containsEntry("targetId", "analysis"));
        assertThat(switched.userMessageId()).isEqualTo(source.userMessageId());
        assertThat(switched.assistantMessageId()).isNotEqualTo(source.assistantMessageId());
        assertStreamStatus(binding);

        // 新编排实例和空缓存只从持久化Binding恢复；测试ID区间分开，避免测试生成器重置冲突。
        FinanceEXChatService restarted = service(1000);
        ChatRun next = execute(restarted, new ChatCommand("next", null, null, source.sessionId(), null,
                "web", "Next question", List.of(), Map.of()));
        assertResumed(binding, next);
        ChatRun manual = execute(restarted, new ChatCommand("manual", null, null, source.sessionId(), null,
                "web", "Same expert", List.of(), SelectedIntentContext.attach(Map.of(), "finance", "Finance"),
                "DOMAIN_EXPERT", "analysis", ChatRunMode.NEXT, null, null, null));
        assertResumed(binding, manual);
        assertThat(intentCalls).hasValue(priorIntent);
        assertThat(useCaseCalls).hasValue(priorUseCase);
    }

    @Test
    void sameExpertCandidateWithAttachmentsPreservesBindingAndTrustedDocuments() {
        withAttachments = true;
        FinanceEXChatService service = service(0);
        ChatRun source = execute(service, initial("DOMAIN_EXPERT"));
        RuntimeBinding before = relayBinding();
        ChatRun switched = switchCandidate(service, source, "RE_analysis");
        assertResumed(before, switched);
        assertThat(requests.getLast().attachments()).singleElement()
                .satisfies(attachment -> assertThat(attachment.documentId()).isEqualTo("doc1"));
        assertThat(requests.getLast().documents()).singleElement()
                .satisfies(document -> assertThat(document.id()).isEqualTo("doc1"));
    }

    @Test
    void expiredSameExpertIsNotRestoredAndForceRerouteKeepsExistingCancellationRule() {
        FinanceEXChatService service = service(0);
        ChatRun source = execute(service, initial("DOMAIN_EXPERT"));
        RuntimeBinding expired = relayBinding();
        bindings.save(expired.withRun(expired.lastRunId(), Instant.now().minusSeconds(1)));
        ChatRun switched = switchCandidate(service, source, "RE_analysis");
        assertThat(relayBinding().id()).isNotEqualTo(expired.id());
        assertThat(requests.getLast().runtimeSessionMode()).isEqualTo(RuntimeSessionMode.NEW);
        RuntimeBinding beforeReroute = relayBinding();
        execute(service, new ChatCommand("reroute", null, null, switched.sessionId(), null, "web",
                "Route again", List.of(), Map.of(), null, null, ChatRunMode.NEXT,
                null, null, null, "user_correction"));
        assertThat(intentCalls).hasValue(1);
        assertThat(requests.getLast().runtimeSessionMode()).isEqualTo(RuntimeSessionMode.NEW);
        assertThat(bindings.findById(beforeReroute.id()).orElseThrow().status())
                .isEqualTo(RuntimeBindingStatus.CANCELLED);
    }

    @Test
    void candidateCanRestoreExistingResumableExpert() {
        nonSticky = true;
        FinanceEXChatService service = service(0);
        ChatRun source = execute(service, initial("DOMAIN_EXPERT"));
        RuntimeBinding before = relayBinding();
        assertThat(before.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
        switchCandidate(service, source, "RE_analysis");
        assertThat(relayBinding().id()).isEqualTo(before.id());
        assertThat(requests.getLast().runtimeSessionId()).isEqualTo(before.runtimeSessionId());
        assertThat(requests.getLast().runtimeSessionMode()).isEqualTo(RuntimeSessionMode.RESUME);
        assertThat(intentCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"candidate-skill-switch", "selectedDomainAgent"})
    void failedPersistenceBarrierNeverSubscribesExpertOrLeavesNewActiveBinding(String stage) {
        FinanceEXChatService service = service(0);
        ChatRun source = execute(service, initial("DOMAIN_AGENT"));
        failSourceType = stage;
        ChatRun failed = switchCandidate(service, source, "RE_analysis");
        assertThat(failed.status()).isEqualTo(ChatRunStatus.FAILED);
        assertThat(requests).isEmpty();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(bindings.bindingsForProvider("relay"))
                        .noneMatch(binding -> binding.status() == RuntimeBindingStatus.ACTIVE));
        assertThat(intentCalls).hasValue(0);
    }

    @Test
    void nonStickyCandidateReroutesAndRestoresResumableSession() {
        nonSticky = true;
        FinanceEXChatService service = service(0);
        ChatRun source = execute(service, initial("DOMAIN_AGENT"));
        ChatRun switched = switchCandidate(service, source, "RE_analysis");
        RuntimeBinding binding = relayBinding();
        assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
        assertThat(intentCalls).hasValue(0);
        execute(service, new ChatCommand("next", null, null, switched.sessionId(), null, "web",
                "Route again", List.of(), Map.of()));
        assertThat(intentCalls).hasValue(1);
        assertThat(useCaseCalls).hasValue(1);
        assertThat(relayBinding().id()).isEqualTo(binding.id());
        assertThat(requests.getLast().runtimeSessionMode()).isEqualTo(RuntimeSessionMode.RESUME);
        assertThat(requests.getLast().runtimeSessionId()).isEqualTo(binding.runtimeSessionId());
        assertThat(relayBinding().status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
    }

    @Test
    void switchingOtherExpertsAndBackToDomainAgentDoesNotReuseCancelledBinding() {
        FinanceEXChatService service = service(0);
        ChatRun source = execute(service, initial("DOMAIN_EXPERT"));
        RuntimeBinding old = relayBinding();
        ChatRun switched = switchCandidate(service, source, "RE_other");
        assertThat(requests.getLast().runtimeSessionMode()).isEqualTo(RuntimeSessionMode.NEW);
        assertThat(relayBinding().id()).isNotEqualTo(old.id());
        assertThat(bindings.findById(old.id()).orElseThrow().status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        ChatRun domain = switchCandidate(service, switched, "skill-a");
        assertThat(domain.routeType()).isEqualTo("DOMAIN_AGENT");
        assertThat(bindings.bindingsForProvider("relay"))
                .allSatisfy(binding -> assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.CANCELLED));
        switchCandidate(service, domain, "RE_analysis");
        assertThat(requests.getLast().runtimeSessionMode()).isEqualTo(RuntimeSessionMode.NEW);
        assertThat(relayBinding().id()).isNotEqualTo(old.id());
    }

    @Test
    void candidateQuestionnaireRemainsActiveAndAnswerUsesSameSession() {
        nonSticky = true;
        FinanceEXChatService service = service(0);
        ChatRun source = execute(service, initial("DOMAIN_AGENT"));
        questionnaire = true;
        ChatRun waiting = switchCandidate(service, source, "RE_analysis");
        assertThat(waiting.status()).isEqualTo(ChatRunStatus.WAITING_USER);
        RuntimeBinding binding = relayBinding();
        assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        var interaction = interactions.requests.values().iterator().next();
        assertThat(interaction.runtimeSessionId()).isEqualTo(binding.runtimeSessionId());
        ChatCommand response = new ChatCommand("answer", null, null, waiting.sessionId(), null, "web", null,
                List.of(), Map.of(), null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null, null,
                interaction.id(), true, "once", Map.of("label", Map.of("Period?", "Month")));
        var started = service.startRun(user, response).block(Duration.ofSeconds(5));
        assertThat(started).isNotNull();
        awaitTerminal(started.runId());
        assertThat(relayBinding().status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
        assertThat(relayBinding().id()).isEqualTo(binding.id());
        assertThat(intentCalls).hasValue(0);
    }

    private void assertResumed(RuntimeBinding binding, ChatRun run) {
        assertThat(requests.getLast().runtimeSessionMode()).isEqualTo(RuntimeSessionMode.RESUME);
        assertThat(requests.getLast().runtimeSessionId()).isEqualTo(binding.runtimeSessionId());
        assertThat(relayBinding().id()).isEqualTo(binding.id());
        assertExpert(relayBinding(), run);
    }

    private void assertExpert(RuntimeBinding binding, ChatRun run) {
        assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(binding.runtimeSessionId()).isNotEqualTo(run.sessionId());
        assertThat(binding.lastRunId()).isEqualTo(run.id());
        assertThat(binding.leafMessageId()).isEqualTo(run.assistantMessageId());
        assertThat(binding.metadata()).containsEntry("runtimeProfile", "DOMAIN_EXPERT")
                .containsEntry("relayRoleName", "analysis").containsEntry("relayAppMode", "domain_expert")
                .containsEntry("routeSource", "front-selected").containsEntry("relayExpertPinned", true)
                .containsEntry("invocationSkillId", "analysis").containsEntry("intentCode", "finance")
                .containsEntry("intentName", "Finance");
        assertThat(requests.getLast().routeTarget().runtimeRoleName()).isEqualTo("analysis");
        assertThat(requests.getLast().routeTarget().invocationSkillId()).isEqualTo("analysis");
        assertThat(messages.findByOwnerAndId(user.tenantId(), user.ownerUserId(), run.assistantMessageId())
                .orElseThrow().metadataJson()).contains("\"skillId\":\"analysis\"");
    }

    private void assertStreamStatus(RuntimeBinding binding) {
        var bindingService = new RuntimeBindingApplicationService(
                bindings, runtimeBindingCache(), new SequentialIdGenerator(), Duration.ZERO, "relay");
        var providers = new StaticListableBeanFactory(Map.of("bindings", bindingService));
        var runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                new PermissionChecker(), sessions, null, null, null,
                providers.getBeanProvider(RuntimeBindingApplicationService.class));
        var status = runService.streamStatus(user, binding.chatSessionId());
        assertThat(status.activeRunId()).isNull();
        assertThat(status.bindingTargetType()).isEqualTo("DOMAIN_EXPERT");
        assertThat(status.bindingTargetId()).isEqualTo("analysis");
        assertThat(status.bindingIntentCode()).isEqualTo("finance");
        assertThat(status.bindingIntentName()).isEqualTo("Finance");
        assertThat(status.bindingRouteSource()).isEqualTo("front-selected");
    }

    private RuntimeBinding relayBinding() {
        return bindings.bindingsForProvider("relay").stream()
                .filter(binding -> binding.status() != RuntimeBindingStatus.CANCELLED).findFirst().orElseThrow();
    }

    private ChatRun execute(FinanceEXChatService service, ChatCommand command) {
        List<ChatEvent> output = service.executeRun(user, command).collectList().block(Duration.ofSeconds(5));
        assertThat(output).isNotEmpty();
        assertThat(output.getLast().type()).isEqualTo("run.completed");
        return runs.findById(output.getLast().runId()).orElseThrow();
    }

    private ChatRun switchCandidate(FinanceEXChatService service, ChatRun source, String skill) {
        Object orchestrator = ReflectionTestUtils.getField(service, "orchestrator");
        var stop = (ChatRunStopCoordinator) ReflectionTestUtils.getField(orchestrator, "stopCoordinator");
        var switcher = new CandidateDomainAgentSwitchApplicationService(
                (ChatRunApplicationService) ReflectionTestUtils.getField(stop, "chatRunService"),
                (SessionApplicationService) ReflectionTestUtils.getField(stop, "sessionService"), messages,
                documentFacade(), stop,
                (ChatRunStartCoordinator) ReflectionTestUtils.getField(orchestrator, "runStartCoordinator"),
                (ChatRunExecutionCoordinator) ReflectionTestUtils.getField(orchestrator, "runExecutionCoordinator"),
                new CandidateSwitchRouteTraceService(events, new ObjectMapper()), interactions,
                new CandidateSwitchTargetResolver("RE_", new SensitiveInformationAccessNameResolver("")));
        var started = switcher.switchDomainAgent(user, TraceContext.empty(), new CandidateDomainAgentSwitchCommand(
                source.id(), source.userMessageId(), skill, SelectedIntentContext.attach(Map.of(), "finance", "Finance"),
                null, "finance_pc_entry", "mobile"), RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(5));
        assertThat(started).isNotNull();
        awaitTerminal(started.runId());
        return runs.findById(started.runId()).orElseThrow();
    }

    private void awaitTerminal(String runId) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(events.events)
                .anyMatch(event -> runId.equals(event.runId())
                        && ("run.completed".equals(event.type()) || "run.waiting_user".equals(event.type())
                        || "run.failed".equals(event.type()))));
    }

    private ChatCommand initial(String type) {
        String target = "DOMAIN_AGENT".equals(type) ? "skill-a" : "INTENT_EXPERT".equals(type) ? "scope-a" : "analysis";
        ChatCommand command = new ChatCommand("source", null, null, null, null, "web", "Analyze",
                withAttachments ? List.of(new AttachmentRef("doc1", "client.pdf", "application/pdf", 128L))
                        : List.of(), Map.of(), type, target, ChatRunMode.NEXT, null, null, null);
        return "INTENT_EXPERT".equals(type)
                ? command.withIntentExpertScope(new IntentExpertScope("scope-a", "Scope A", "entry_a")) : command;
    }

    private FinanceEXChatService service(int idOffset) {
        var routing = new RouteSignalApplicationService(request -> {
            useCaseCalls.incrementAndGet();
            return UseCaseMatchResult.notMatched("not matched");
        }, intentAgent((command, memory, owner) -> {
            intentCalls.incrementAndGet();
            return new IntentDecision("finance", "Finance", TaskComplexity.SIMPLE, 0.99, true,
                    "RE_analysis", Map.of("routeAction", "ROUTE_SINGLE"), List.of(), Map.of());
        }), new RoutingPolicy(0.85, 0.85, "RE_"), new RouteSignalProperties(true, true));
        var domain = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "Domain answer"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        var service = financeServiceWithDomainClientAndBindings(sessions, messages, runs, events, routing,
                domain, relay(), bindings, new DomainAgentProperties(), liveEventBus(), interactions);
        Object orchestrator = ReflectionTestUtils.getField(service, "orchestrator");
        Object stop = ReflectionTestUtils.getField(orchestrator, "stopCoordinator");
        Object sessionService = ReflectionTestUtils.getField(stop, "sessionService");
        ((SequentialIdGenerator) ReflectionTestUtils.getField(sessionService, "idGenerator")).sequence.set(idOffset);
        return service;
    }

    private RelayAgentRuntime relay() {
        return new RelayAgentRuntime(new RelayRuntimeProtocolAdapter() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                requests.add(request);
                String downstream = request.runtimeSessionMode() == RuntimeSessionMode.NEW
                        ? "downstream-" + request.runId() : request.runtimeSessionId();
                Sinks.One<Void> ready = Sinks.one();
                ChatEvent metadata = new PersistenceAcknowledgedEvent(RuntimeEvent.metadata(request.runId(),
                        request.sessionId(), Map.of("source", "relay", "sourceType", "session-ready",
                                "runtimeSessionId", downstream)), ready);
                ChatEvent output = questionnaire ? RuntimeEvent.card(request.runId(), request.sessionId(), Map.of(
                        "source", "relay", "sourceType", "approval-request", "approval_id", "approval-1",
                        "operation_type", "questionnaire", "mode", "questionnaire", "message", "Choose period",
                        "questions", List.of(Map.of("question", "Period?", "multi_select", false,
                                "options", List.of(Map.of("label", "Month"))))))
                        : MessageSnapshotEvent.of(request.runId(), request.sessionId(), "Expert answer");
                return Flux.concat(Flux.just(metadata), ready.asMono().thenReturn(output));
            }

            @Override
            public boolean supportsUserResponseContinuation() {
                return true;
            }

            @Override
            public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
                assertThat(request.runtimeSessionId()).isEqualTo(relayBinding().runtimeSessionId());
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "Continued answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        });
    }
}
