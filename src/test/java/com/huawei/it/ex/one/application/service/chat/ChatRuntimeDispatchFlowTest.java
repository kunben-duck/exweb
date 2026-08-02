package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
class ChatRuntimeDispatchFlowTest extends ChatFlowTestSupport {
    @Test
    void ownerLossAfterDirectBindingCreationCancelsUnstartedBinding() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        OwnerRejectingBindingActivationRepository bindings =
                new OwnerRejectingBindingActivationRepository(executions, "domain-agent");
        AtomicInteger domainAgentCalls = new AtomicInteger();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                domainAgentCalls.incrementAndGet();
                return Flux.just(MessageDeltaEvent.of(
                        request.runId(), request.sessionId(), "must not be called"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions,
                messages,
                runs,
                events,
                runtimeRouteService(),
                domainClient,
                noopRuntime(),
                bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                liveEventBus(),
                new InMemoryInteractionRequestRepository(),
                runtimeBindingCache(),
                null,
                documentFacade(),
                executions);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                                "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of(),
                                "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitValue(bindings.activatedStatus, RuntimeBindingStatus.CANCELLED,
                "unstarted direct binding cancellation");
        assertThat(domainAgentCalls).hasValue(0);
    }

    @Test
    void explicitDomainAgentTargetRoutesAndBindsDomainAgent() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        NeverCancelRunCache runCache = new NeverCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new SequentialIdGenerator();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        DocumentFacade documents = documentFacade();
        AtomicReference<RuntimeForwardHeaders> capturedHeaders = new AtomicReference<>();
        AtomicReference<DomainAgentRequest> capturedRequest = new AtomicReference<>();
        DomainAgentExecutor domainAgentExecutor = new DomainAgentExecutor(new DomainAgentClient() {
            @Override public Flux<ChatEvent> query(DomainAgentRequest request) {
                capturedHeaders.set(request.forwardHeaders());
                capturedRequest.set(request);
                return Flux.just(MessageDeltaEvent.of(request.runId(), request.sessionId(), "domain answer"));
            }
            @Override public Mono<Void> cancel(DomainAgentCancelRequest request) { return Mono.empty(); }
        }, documents, limiter);
        SessionApplicationService sessionService =
                new SessionApplicationService(sessions, messages, ids, permissionChecker);
        ChatRunApplicationService runService =
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions);

        FinanceEXChatService service = ChatFlowTestFixture.service(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(bindings, runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                domainAgentExecutor,
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documents,
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                ids
        );
        Map<String, Object> selectedMetadata = SelectedIntentContext.attach(
                Map.of("skillId", "skill-other"), "fund_management", "资金管理");
        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", null,
                        List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)), selectedMetadata,
                        "DOMAIN_AGENT", "skill-tax", ChatRunMode.NEXT, null, null, null,
                        null, null, null, null, Map.of(), "fund-app", "资金助手"),
                        RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192)))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "runtime.metadata".equals(event.type())
                        && "DOMAIN_AGENT".equals(event.payload().get("targetType"))
                        && "skill-tax".equals(event.payload().get("targetId"))
                        && "selected_domain_agent".equals(event.payload().get("metadataType"))
                        && "fund_management".equals(event.payload().get("intentId"))
                        && "资金管理".equals(event.payload().get("intentName")))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .expectNextMatches(event -> "run.completed".equals(event.type()))
                .verifyComplete();

        assertThat(capturedHeaders.get()).isNotNull();
        assertThat(capturedHeaders.get().cookieHeader()).isEqualTo("sid=abc");
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().query()).isEmpty();
        assertThat(capturedRequest.get().documents()).extracting(UploadedDocument::id).containsExactly("doc1");
        assertThat(capturedRequest.get().metadata())
                .containsExactlyEntriesOf(Map.of("skillId", "skill-other"));
        assertThat(bindings.saved.metadata()).containsEntry("intentCode", "fund_management")
                .containsEntry("intentName", "资金管理");
        ChatRun run = runs.runs.values().stream().findFirst().orElseThrow();
        assertThat(run.routeType()).isEqualTo("DOMAIN_AGENT");
        assertThat(run.agentCode()).isEqualTo("skill-tax");
        assertThat(run.runtimeProvider()).isEqualTo("domain-agent");
        assertThat(run.metadata()).containsExactlyEntriesOf(Map.of("skillId", "skill-other"));
        ChatSession taggedSession = sessions.findById(run.sessionId()).orElseThrow();
        assertThat(taggedSession.appId()).isEqualTo("fund-app");
        assertThat(taggedSession.appName()).isEqualTo("资金助手");
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEmpty();
                    assertThat(messages.attachments.stream()
                            .filter(attachment -> message.id().equals(attachment.messageId())))
                            .singleElement()
                            .satisfies(attachment -> {
                                assertThat(attachment.documentId()).isEqualTo("doc1");
                                assertThat(attachment.name()).isEqualTo("invoice.pdf");
                            });
                });
        assertThat(messages.parts).anySatisfy(part -> {
            assertThat(part.partType()).isEqualTo("METADATA");
            assertThat(part.payload()).containsEntry("targetId", "skill-tax")
                    .containsEntry("domainAgentId", "skill-tax")
                    .containsEntry("metadataType", "selected_domain_agent")
                    .containsEntry("intentId", "fund_management")
                    .containsEntry("intentName", "资金管理");
        });

        String sessionId = run.sessionId();
        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd2", null, null,
                        sessionId, null, "web", null,
                        List.of(new AttachmentRef("doc2", "another-forged-name.txt", "text/plain", 1L)), Map.of())))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "runtime.metadata".equals(event.type())
                        && "runtime-binding".equals(event.payload().get("routeSource"))
                        && "fund_management".equals(event.payload().get("intentId"))
                        && "资金管理".equals(event.payload().get("intentName")))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .expectNextMatches(event -> "run.completed".equals(event.type()))
                .verifyComplete();

        assertThat(capturedRequest.get().query()).isEmpty();
        assertThat(capturedRequest.get().documents()).extracting(UploadedDocument::id).containsExactly("doc2");
        assertThat(messages.parts.stream()
                .filter(part -> "METADATA".equals(part.partType()))
                .filter(part -> "selected_domain_agent".equals(part.payload().get("metadataType"))))
                .hasSize(2)
                .allSatisfy(part -> assertThat(part.payload())
                        .containsEntry("intentId", "fund_management")
                        .containsEntry("intentName", "资金管理"));
    }

    @Test
    void explicitDomainAgentNextCancelsWaitingInteractionAndUsesCurrentRequest() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        ChatSession session = sessions.save(new ChatSession(
                "session-direct", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-wait-assistant", "session-direct", null, null, 2L, null, now, now));
        messages.save(new ChatMessage(
                "msg-old-user", user.tenantId(), user.ownerUserId(), session.id(), null,
                1L, 0, 1, "user", "旧问题", null, "run-wait", "NORMAL", false,
                null, null, null, null, null, now));
        messages.save(new ChatMessage(
                "msg-wait-assistant", user.tenantId(), user.ownerUserId(), session.id(), "msg-old-user",
                2L, 1, 1, "assistant", "请补充信息", null, "run-wait", "NORMAL", false,
                null, null, null, null, "{\"finishReason\":\"WAITING_USER\"}", now));
        ChatRun waitingRun = new ChatRun(
                "run-wait", user.tenantId(), user.ownerUserId(), session.id(), ChatRunStatus.WAITING_USER,
                "AGENT_RUNTIME", null, "relay", "relay-active-session", ChatRunMode.NEXT,
                null, "msg-old-user", "msg-wait-assistant", 1L, 2L, null, now, now,
                Map.of(), now, now);
        runs.save(waitingRun);
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction-wait", user.tenantId(), user.ownerUserId(), session.id(), waitingRun.id(), null,
                "msg-old-user", "msg-wait-assistant", "relay", "binding-relay-active",
                "relay-active-session", null, ChatInteractionType.AGENT_CLARIFICATION,
                ChatInteractionStatus.WAITING, Map.of("clarifyQuestion", "请补充信息"), Map.of(),
                now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);
        bindings.save(new RuntimeBinding(
                "binding-relay-history", user.tenantId(), user.ownerUserId(), session.id(), "relay",
                "msg-old-user", "relay-history-session", RuntimeBindingStatus.RESUMABLE, "run-history",
                null, now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(1)),
                Map.of("runtimeSessionEstablished", true)));
        bindings.save(new RuntimeBinding(
                "binding-relay-active", user.tenantId(), user.ownerUserId(), session.id(), "relay",
                "msg-wait-assistant", "relay-active-session", RuntimeBindingStatus.ACTIVE, waitingRun.id(),
                null, now, now, Map.of("runtimeSessionEstablished", true)));

        AtomicInteger routeCalls = new AtomicInteger();
        AtomicReference<DomainAgentRequest> capturedRequest = new AtomicReference<>();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                capturedRequest.set(request);
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "直连回答"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, countingRuntimeRouteService(routeCalls), domainClient,
                noopRuntime(), bindings, new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                liveEventBus(), interactions);
        AgentModeProfile requestedMode = new AgentModeProfile(List.of(
                new AgentModeSelection("thinking", "deep", "深度思考")));

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                        "cmd-ordinary", null, null, session.id(), null, "web", "普通追问",
                        List.of(), Map.of())))
                .expectError(ChatInteractionUnavailableException.class)
                .verify();
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(messages.messages).hasSize(2);

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                                "cmd-direct", null, null, session.id(), null, "web", "本轮直连问题",
                                List.of(), Map.of("scene", "current"), "DOMAIN_AGENT", "fund-agent",
                                ChatRunMode.NEXT, null, null, null,
                                null, null, null, null, Map.of(), null, null, requestedMode))
                        .collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type)
                            .containsExactly("run.started", "runtime.metadata", "message.snapshot", "run.completed");
                    assertThat(stream).filteredOn(event -> "selected_domain_agent".equals(
                                    event.payload().get("metadataType")))
                            .allSatisfy(event -> assertThat(event.payload()).doesNotContainKey("agentMode"));
                })
                .verifyComplete();

        assertThat(routeCalls).hasValue(0);
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.CANCELLED);
        assertThat(runs.runs.get(waitingRun.id()).status()).isEqualTo(ChatRunStatus.WAITING_USER);
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().query()).isEqualTo("本轮直连问题");
        assertThat(capturedRequest.get().domainAgentId()).isEqualTo("fund-agent");
        assertThat(capturedRequest.get().metadata()).containsExactlyEntriesOf(Map.of("scene", "current"));
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role()))
                .filteredOn(message -> "本轮直连问题".equals(message.content()))
                .singleElement()
                .extracting(ChatMessage::parentMessageId)
                .isEqualTo("msg-wait-assistant");
        assertThat(bindings.bindingsForProvider("relay"))
                .extracting(RuntimeBinding::status)
                .containsExactlyInAnyOrder(RuntimeBindingStatus.RESUMABLE, RuntimeBindingStatus.CANCELLED);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
                    assertThat(binding.metadata()).containsEntry("routeSource", "front-selected")
                            .containsEntry("domainAgentId", "fund-agent");
                    assertThat(AgentModeBindingContext.fromBinding(binding)).isEqualTo(requestedMode);
                });
    }

    @Test
    void invalidTargetTypeFailsBeforeWritingUserMessageOrRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of(),
                        "BAD", "skill-tax", ChatRunMode.NEXT, null, null, null)))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("targetType 仅支持 DOMAIN_AGENT"))
                .verify();

        assertThat(sessions.sessions).isEmpty();
        assertThat(messages.messages).isEmpty();
        assertThat(runs.runs).isEmpty();
        assertThat(events.events).isEmpty();
    }

    @Test
    void domainAgentTargetWithoutTargetIdFailsBeforeWritingUserMessageOrRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of(),
                        "DOMAIN_AGENT", null, ChatRunMode.NEXT, null, null, null)))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("targetType=DOMAIN_AGENT 时 targetId 不能为空"))
                .verify();

        assertThat(sessions.sessions).isEmpty();
        assertThat(messages.messages).isEmpty();
        assertThat(runs.runs).isEmpty();
        assertThat(events.events).isEmpty();
    }
}
