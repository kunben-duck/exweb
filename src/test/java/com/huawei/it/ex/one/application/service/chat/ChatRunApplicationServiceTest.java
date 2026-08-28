package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunCancelSignal;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
class ChatRunApplicationServiceTest {
    @Test
    void guardedResolvedRouteKeepsOnlyTheLastInvocationSkill() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        ChatRunApplicationService service = service(repository, new InMemoryRunCache());
        repository.save(runningRun());
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance1", 7L);

        service.bindResolvedRoute(
                "run1", RouteTarget.domainAgent("skill-a", "intent-agent"), null, claim, Map.of());
        service.bindResolvedRoute(
                "run1", RouteTarget.domainAgent("skill-b", "intent-agent"), null, claim, Map.of());

        assertThat(MessageSkillContext.runSkillId(repository.saved.metadata())).isEqualTo("skill-b");
    }

    @Test
    void guardedResolvedRouteClearsSkillWhenFinalRouteHasNoInvocationIdentifier() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        ChatRunApplicationService service = service(repository, new InMemoryRunCache());
        repository.save(runningRun().withMetadata(Map.of("existing", "value")));
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance1", 7L);

        service.bindResolvedRoute(
                "run1", RouteTarget.domainAgent("skill-a", "intent-agent"), null, claim, Map.of());
        service.bindResolvedRoute(
                "run1", RouteTarget.agentRuntime("intent-fallback", 0.0, "fallback"), null, claim, Map.of());

        assertThat(MessageSkillContext.runSkillId(repository.saved.metadata())).isNull();
        assertThat(repository.saved.metadata()).containsEntry("existing", "value");
    }

    @Test
    void unguardedDiagnosticRouteUpdateDoesNotRecordInvocationSkill() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        ChatRunApplicationService service = service(repository, new InMemoryRunCache());
        repository.save(runningRun());

        service.bindResolvedRoute(
                "run1", RouteTarget.domainAgent("skill-not-invoked", "user-declined"), null);

        assertThat(MessageSkillContext.runSkillId(repository.saved.metadata())).isNull();
    }

    @Test
    void stopRunningRunMarksCancelAndCancelledEventClosesRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        ChatRun run = runningRun();
        repository.save(run);
        cache.putActive(run);

        var decision = service.requestStop(user(), "run1", "USER_STOP");

        assertThat(decision.appendCancelledEvent()).isTrue();
        assertThat(repository.saved.status()).isEqualTo(ChatRunStatus.CANCELLING);
        assertThat(cache.cancellationSignal("run1")).isEqualTo(ChatRunCancelSignal.REQUESTED);

        service.observeEvent(new StoredChatEvent("run1", "session1", 8L, "run.cancelled",
                Instant.now(), RunCancelledEvent.of("run1", "session1", "USER_STOP").payload()));

        assertThat(repository.saved.status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(repository.saved.lastSeq()).isEqualTo(8L);
        assertThat(cache.getActive("tenant1", "user1", "session1")).isEmpty();
    }

    @Test
    void stopCompletedRunIsIdempotent() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRun completed = runningRun().completed(9L);
        repository.save(completed);

        var decision = service(repository, cache).requestStop(user(), "run1", "USER_STOP");

        assertThat(decision.appendCancelledEvent()).isFalse();
        assertThat(decision.run().status()).isEqualTo(ChatRunStatus.COMPLETED);
        assertThat(cache.cancellationSignal("run1")).isEqualTo(ChatRunCancelSignal.NOT_REQUESTED);
    }

    @Test
    void stopCancellingRunRetriesTerminalSubmissionWithoutRevertingStatus() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRun cancelling = runningRun().cancelling("USER_STOP");
        repository.save(cancelling);

        var decision = service(repository, cache).requestStop(user(), "run1", "USER_STOP");

        assertThat(decision.appendCancelledEvent()).isTrue();
        assertThat(decision.run().status()).isEqualTo(ChatRunStatus.CANCELLING);
        assertThat(cache.cancellationSignal("run1")).isEqualTo(ChatRunCancelSignal.REQUESTED);
    }

    @Test
    void stopDatabaseFailureDoesNotWriteRedisCancellationFlag() {
        FailingStopClaimRunRepository repository = new FailingStopClaimRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun());

        assertThatThrownBy(() -> service.requestStop(user(), "run1", "USER_STOP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stop claim db failure");

        assertThat(repository.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.RUNNING);
        assertThat(cache.cancellationSignal("run1")).isEqualTo(ChatRunCancelSignal.NOT_REQUESTED);
        assertThat(service.shouldAcceptEvent(MessageDeltaEvent.of("run1", "session1", "still running"))).isTrue();
    }

    @Test
    void stopDoesNotAppendCancelledEventWhenTerminalRaceWins() {
        TerminalRaceRunRepository repository = new TerminalRaceRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        repository.save(runningRun());

        var decision = service(repository, cache).requestStop(user(), "run1", "USER_STOP");

        assertThat(decision.appendCancelledEvent()).isFalse();
        assertThat(decision.run().status()).isEqualTo(ChatRunStatus.COMPLETED);
    }

    @Test
    void shouldUseDatabaseOnlyForTerminalAndCancelledEventAdmission() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun().cancelling("USER_STOP"));

        // 非终态事件不再预查 run 表；最终拒绝由 guarded insert 的 r.status='RUNNING' 条件负责。
        assertThat(service.shouldAcceptEvent(MessageDeltaEvent.of("run1", "session1", "late delta"))).isTrue();
        assertThat(service.shouldAcceptEvent(RunCompletedEvent.of("run1", "session1"))).isFalse();
        assertThat(service.shouldAcceptEvent(RunCancelledEvent.of("run1", "session1", "USER_STOP"))).isTrue();
    }

    @Test
    void observeEventDoesNotUpdateRunForMessageDeltaOrMessageCompleted() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun());

        service.observeEvent(MessageDeltaEvent.of("run1", "session1", "first delta"));
        service.observeEvent(new StoredChatEvent("run1", "session1", 4L, "message.completed", Instant.now(), Map.of()));

        assertThat(repository.saved.status()).isEqualTo(ChatRunStatus.RUNNING);
        assertThat(repository.saved.lastSeq()).isNull();
    }

    @Test
    void observeRuntimeMetadataUpdatesRuntimeSessionIdForCrossInstanceCancel() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun().withRuntimeSessionId("generated-session"));

        service.observeEvent(RuntimeEvent.metadata("run1", "session1", Map.of(
                "metadataType", "relay_session_ready",
                "runtimeSessionId", "relay-session-actual")));

        assertThat(repository.saved.runtimeSessionId()).isEqualTo("relay-session-actual");
    }

    @Test
    void observeLiveOnlyRuntimeMetadataUpdatesSessionWithoutAdvancingPersistedSequence() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun().withFirstSeq(7L));

        service.observeLiveOnlyRuntimeState(new StoredChatEvent(
                "run1",
                "session1",
                11L,
                "runtime.metadata",
                Instant.now(),
                Map.of("runtimeSessionId", "relay-session-live-only")));

        assertThat(repository.saved.runtimeSessionId()).isEqualTo("relay-session-live-only");
        assertThat(repository.saved.lastSeq()).isEqualTo(7L);
    }

    @Test
    void shouldRejectEventsImmediatelyWhenRedisCancelFlagExists() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun());
        cache.markCancellationRequested("run1");

        assertThat(service.shouldAcceptEvent(MessageDeltaEvent.of("run1", "session1", "late delta"))).isFalse();
    }

    @Test
    void streamStatusUsesActiveRunAndLatestSeq() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        InMemoryEventStore eventStore = new InMemoryEventStore(11L);
        ChatRun run = runningRun().withFirstSeq(1L).withLastSeq(3L);
        repository.save(run);
        cache.putActive(run);
        ChatRunApplicationService service = new ChatRunApplicationService(repository, cache, eventStore,
                new PermissionChecker(), new FixedSessionRepository());

        var status = service.streamStatus(user(), "session1");

        assertThat(status.latestSeq()).isEqualTo(11L);
        assertThat(status.activeRunId()).isEqualTo("run1");
        assertThat(status.activeRunStatus()).isEqualTo(ChatRunStatus.RUNNING);
        assertThat(status.activeStreamTopicId()).isEqualTo("chat-run-run1");
        assertThat(status.activeRunFirstSeq()).isEqualTo(1L);
        assertThat(status.activeRunLastSeq()).isEqualTo(3L);
        assertThat(status.cancellable()).isTrue();
        assertThat(status.waitingUserInput()).isFalse();
        assertThat(status.interactionId()).isNull();
        assertThat(status.interactionType()).isNull();
        assertThat(status.assistantMessageId()).isNull();
    }

    @Test
    void streamStatusReturnsDomainAgentAsyncRunningPhaseAndDeadline() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        Instant expiresAt = Instant.parse("2026-08-29T10:00:00Z");
        ChatRun run = runningRun()
                .withAssistantMessageId("msg-assistant")
                .withMetadata(DomainAgentAsyncTaskMetadata.runningOverlay("msg-assistant", expiresAt));
        repository.save(run);
        cache.putActive(run);
        ChatRunApplicationService service = new ChatRunApplicationService(
                repository, cache, new InMemoryEventStore(17L),
                new PermissionChecker(), new FixedSessionRepository());

        var status = service.streamStatus(user(), "session1");

        assertThat(status.activeRunStatus()).isEqualTo(ChatRunStatus.RUNNING);
        assertThat(status.cancellable()).isTrue();
        assertThat(status.activeRunPhase()).isEqualTo("ASYNC_RUNNING");
        assertThat(status.asyncExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void streamStatusReturnsAssistantAssociationForReusableActiveContinuation() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRun run = runningRun()
                .withFirstSeq(21L)
                .withMetadata(Map.of(
                        "interactionId", "interaction-1",
                        "interactionType", ChatInteractionType.AGENT_CLARIFICATION.name(),
                        "interactionMessageStrategy", "REUSE_ASSISTANT",
                        "interactionAssistantMessageId", "message-assistant"));
        repository.save(run);
        cache.putActive(run);
        ChatRunApplicationService service = new ChatRunApplicationService(
                repository, cache, new InMemoryEventStore(23L),
                new PermissionChecker(), new FixedSessionRepository());

        var status = service.streamStatus(user(), "session1");

        assertThat(status.activeRunId()).isEqualTo("run1");
        assertThat(status.waitingUserInput()).isFalse();
        assertThat(status.interactionId()).isEqualTo("interaction-1");
        assertThat(status.interactionType()).isEqualTo("AGENT_CLARIFICATION");
        assertThat(status.assistantMessageId()).isEqualTo("message-assistant");
    }

    @Test
    void streamStatusDoesNotReuseAssistantForNewTurnIntentContinuation() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRun run = runningRun()
                .withFirstSeq(31L)
                .withMetadata(Map.of(
                        "interactionId", "interaction-2",
                        "interactionType", ChatInteractionType.INTENT_CLARIFICATION.name(),
                        "interactionMessageStrategy", "NEW_TURN",
                        "interactionAssistantMessageId", "message-old-assistant"));
        repository.save(run);
        cache.putActive(run);
        ChatRunApplicationService service = new ChatRunApplicationService(
                repository, cache, new InMemoryEventStore(33L),
                new PermissionChecker(), new FixedSessionRepository());

        var status = service.streamStatus(user(), "session1");

        assertThat(status.activeRunId()).isEqualTo("run1");
        assertThat(status.waitingUserInput()).isFalse();
        assertThat(status.interactionId()).isNull();
        assertThat(status.interactionType()).isNull();
        assertThat(status.assistantMessageId()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamStatusReturnsAmbiguousRouteAutoSelectionDeadline() {
        ChatInteractionApplicationService interactionService =
                mock(ChatInteractionApplicationService.class);
        ObjectProvider<ChatInteractionApplicationService> interactionProvider =
                mock(ObjectProvider.class);
        when(interactionProvider.getIfAvailable()).thenReturn(interactionService);
        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction-1",
                "tenant1",
                "user1",
                "session1",
                "run-a",
                null,
                "message-user",
                "message-assistant",
                "intent-agent",
                null,
                null,
                null,
                ChatInteractionType.INTENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                Map.of(
                        "clarificationType", "AMBIGUOUS_ROUTE",
                        "autoSelectAt", "2026-07-30T10:00:30Z",
                        "autoSelectTimeoutMs", 30_000L),
                Map.of(),
                now.plusSeconds(3600),
                null,
                null,
                now,
                now);
        when(interactionService.findWaiting(user(), "session1"))
                .thenReturn(Optional.of(waiting));
        ChatRunApplicationService service = new ChatRunApplicationService(
                new InMemoryRunRepository(),
                new InMemoryRunCache(),
                new InMemoryEventStore(12L),
                new PermissionChecker(),
                new FixedSessionRepository(),
                null,
                null,
                interactionProvider,
                null);

        var status = service.streamStatus(user(), "session1");

        assertThat(status.waitingUserInput()).isTrue();
        assertThat(status.interactionId()).isEqualTo("interaction-1");
        assertThat(status.autoSelectAt()).isEqualTo(Instant.parse("2026-07-30T10:00:30Z"));
        assertThat(status.autoSelectTimeoutMs()).isEqualTo(30_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamStatusReturnsRelayQuestionnaireAutoActionDeadline() {
        ChatInteractionApplicationService interactionService =
                mock(ChatInteractionApplicationService.class);
        ObjectProvider<ChatInteractionApplicationService> interactionProvider =
                mock(ObjectProvider.class);
        when(interactionProvider.getIfAvailable()).thenReturn(interactionService);
        Instant now = Instant.parse("2026-08-01T10:00:00Z");
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction-relay",
                "tenant1",
                "user1",
                "session1",
                "run-a",
                null,
                "message-user",
                "message-assistant",
                "relay",
                "binding-relay",
                "relay-session-1",
                "approval-1",
                ChatInteractionType.AGENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                Map.of(
                        "sourceType", "approval-request",
                        "operation_type", "questionnaire",
                        "autoActionAt", "2026-08-01T10:00:30Z",
                        "autoActionTimeoutMs", 30_000L,
                        "autoActionType", "IGNORE_QUESTIONNAIRE"),
                Map.of(),
                now.plusSeconds(3600),
                null,
                null,
                now,
                now);
        when(interactionService.findWaiting(user(), "session1"))
                .thenReturn(Optional.of(waiting));
        ChatRunApplicationService service = new ChatRunApplicationService(
                new InMemoryRunRepository(),
                new InMemoryRunCache(),
                new InMemoryEventStore(12L),
                new PermissionChecker(),
                new FixedSessionRepository(),
                null,
                null,
                interactionProvider,
                null);

        var status = service.streamStatus(user(), "session1");

        assertThat(status.waitingUserInput()).isTrue();
        assertThat(status.waitingSourceRunId()).isEqualTo("run-a");
        assertThat(status.interactionId()).isEqualTo("interaction-relay");
        assertThat(status.autoActionAt()).isEqualTo(Instant.parse("2026-08-01T10:00:30Z"));
        assertThat(status.autoActionTimeoutMs()).isEqualTo(30_000L);
        assertThat(status.autoActionType()).isEqualTo("IGNORE_QUESTIONNAIRE");
        assertThat(status.autoSelectAt()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamStatusReturnsRouteSwitchAutoApprovalDeadline() {
        ChatInteractionApplicationService interactionService =
                mock(ChatInteractionApplicationService.class);
        ObjectProvider<ChatInteractionApplicationService> interactionProvider =
                mock(ObjectProvider.class);
        when(interactionProvider.getIfAvailable()).thenReturn(interactionService);
        Instant now = Instant.parse("2026-08-05T10:00:00Z");
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction-switch",
                "tenant1",
                "user1",
                "session1",
                "run-a",
                null,
                "message-user",
                "message-assistant",
                "domain-agent",
                "binding-domain",
                "domain-session-1",
                null,
                ChatInteractionType.ROUTE_SWITCH_CONFIRMATION,
                ChatInteractionStatus.WAITING,
                Map.of(
                        "source", "chatservice",
                        "sourceType", "route-switch-confirmation-request",
                        "autoActionAt", "2026-08-05T10:00:30Z",
                        "autoActionTimeoutMs", 30_000L,
                        "autoActionType", "APPROVE_ROUTE_SWITCH"),
                Map.of(),
                now.plusSeconds(3600),
                null,
                null,
                now,
                now);
        when(interactionService.findWaiting(user(), "session1"))
                .thenReturn(Optional.of(waiting));
        ChatRunApplicationService service = new ChatRunApplicationService(
                new InMemoryRunRepository(),
                new InMemoryRunCache(),
                new InMemoryEventStore(12L),
                new PermissionChecker(),
                new FixedSessionRepository(),
                null,
                null,
                interactionProvider,
                null);

        var status = service.streamStatus(user(), "session1");

        assertThat(status.waitingUserInput()).isTrue();
        assertThat(status.interactionType()).isEqualTo("ROUTE_SWITCH_CONFIRMATION");
        assertThat(status.autoActionAt()).isEqualTo(Instant.parse("2026-08-05T10:00:30Z"));
        assertThat(status.autoActionTimeoutMs()).isEqualTo(30_000L);
        assertThat(status.autoActionType()).isEqualTo("APPROVE_ROUTE_SWITCH");
        assertThat(status.autoSelectAt()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamStatusReturnsModeOnlyForActiveDomainAgentBinding() {
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        ObjectProvider<RuntimeBindingApplicationService> bindingProvider = mock(ObjectProvider.class);
        when(bindingProvider.getIfAvailable()).thenReturn(bindingService);
        AgentModeProfile mode = new AgentModeProfile(List.of(
                new AgentModeSelection("thinking", "deep", "深度思考")));
        Instant now = Instant.now();
        RuntimeBinding domainBinding = new RuntimeBinding(
                "binding1", "tenant1", "user1", "session1", "domain-agent", "runtime1",
                RuntimeBindingStatus.ACTIVE, "run1", null, now, now,
                AgentModeBindingContext.apply(Map.of("domainAgentId", "fund-agent"), mode));
        when(bindingService.findActiveBySession("tenant1", "user1", "session1"))
                .thenReturn(Optional.of(domainBinding));
        ChatRunApplicationService service = new ChatRunApplicationService(
                new InMemoryRunRepository(), new InMemoryRunCache(), new InMemoryEventStore(0L),
                new PermissionChecker(), new FixedSessionRepository(), null, null, null, bindingProvider);

        assertThat(service.streamStatus(user(), "session1").bindingAgentMode()).isEqualTo(mode);

        RuntimeBinding relayBinding = new RuntimeBinding(
                "binding2", "tenant1", "user1", "session1", "relay", "runtime2",
                RuntimeBindingStatus.ACTIVE, "run2", null, now, now,
                AgentModeBindingContext.apply(Map.of(), mode));
        when(bindingService.findActiveBySession("tenant1", "user1", "session1"))
                .thenReturn(Optional.of(relayBinding));

        assertThat(service.streamStatus(user(), "session1").bindingAgentMode()).isNull();

        Map<String, Object> expertMetadata = new HashMap<>(RuntimeProfileMetadata.bindingMetadata(
                com.huawei.it.ex.one.domain.routing.RuntimeProfile.DOMAIN_EXPERT,
                "delegate", "domain_expert", "financial-analysis"));
        expertMetadata.put(RuntimeProfileMetadata.RELAY_EXPERT_PINNED_KEY, true);
        expertMetadata.put("intentCode", "finance_analysis");
        expertMetadata.put("intentName", "经营分析专家");
        expertMetadata.put("routeSource", "front-selected");
        RuntimeBinding pinnedExpert = new RuntimeBinding(
                "binding3", "tenant1", "user1", "session1", "relay", "runtime3",
                RuntimeBindingStatus.ACTIVE, "run3", null, now, now, expertMetadata);
        when(bindingService.findActiveBySession("tenant1", "user1", "session1"))
                .thenReturn(Optional.of(pinnedExpert));

        var expertStatus = service.streamStatus(user(), "session1");
        assertThat(expertStatus.bindingProvider()).isEqualTo("relay");
        assertThat(expertStatus.bindingTargetType()).isEqualTo("DOMAIN_EXPERT");
        assertThat(expertStatus.bindingTargetId()).isEqualTo("financial-analysis");
        assertThat(expertStatus.bindingIntentCode()).isEqualTo("finance_analysis");
        assertThat(expertStatus.bindingIntentName()).isEqualTo("经营分析专家");
        assertThat(expertStatus.bindingRouteSource()).isEqualTo("front-selected");
    }

    @Test
    void findOwnedRunsByIdsReturnsOnlyCurrentUsersRuns() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        ChatRunApplicationService service = service(repository, new InMemoryRunCache());
        repository.save(run("run1", "user1", "relay"));
        repository.save(run("run2", "user1", "domain-agent"));
        repository.save(run("run3", "other", "relay"));

        Map<String, ChatRun> runs = service.findOwnedRunsByIds(user(),
                java.util.Arrays.asList("run1", "run2", "run3", "run1", "", null));

        assertThat(runs).containsOnlyKeys("run1", "run2");
        assertThat(runs.get("run1").runtimeProvider()).isEqualTo("relay");
        assertThat(runs.get("run2").runtimeProvider()).isEqualTo("domain-agent");
    }

    @Test
    void bindRuntimeProviderRecordsIntentAgentForClarificationRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        ChatRunApplicationService service = service(repository, new InMemoryRunCache());
        repository.save(runningRun().withResolvedRoute("SYSTEM_RESPONSE", null, null, null));

        ChatRun updated = service.bindRuntimeProvider("run1", "intent-agent");

        assertThat(updated.runtimeProvider()).isEqualTo("intent-agent");
        assertThat(repository.findById("run1")).get()
                .extracting(ChatRun::runtimeProvider)
                .isEqualTo("intent-agent");
    }

    @Test
    void createRunningRejectsWhenSessionAlreadyHasActiveRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRun active = runningRun();
        repository.save(active);
        cache.putActive(active);
        ChatRunApplicationService service = service(repository, cache);

        assertThatThrownBy(() -> service.createRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.domain.routing.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                Map.of(),
                com.huawei.it.ex.one.domain.chat.ChatRunMode.NEXT,
                null,
                null
        ))).isInstanceOf(com.huawei.it.ex.one.domain.chat.ActiveRunExistsException.class)
                .hasMessageContaining("ACTIVE_RUN_EXISTS");
    }

    @Test
    void createRunningUsesDatabaseInsertInsteadOfRedisClaim() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache() {
            @Override
            public boolean tryClaimActive(ChatRun run) {
                return false;
            }
        };

        ChatRun created = service(repository, cache).createRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.domain.routing.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                Map.of(),
                com.huawei.it.ex.one.domain.chat.ChatRunMode.NEXT,
                null,
                null
        ));

        assertThat(created.id()).isEqualTo("run2");
        assertThat(repository.findById("run2")).contains(created);
        assertThat(cache.getActive("tenant1", "user1", "session1")).contains(created);
    }

    @Test
    void createRunningRemovesClientSuppliedPersistencePolicy() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("businessKey", "businessValue");
        metadata.put(AgentDataPersistenceMetadata.RUN_METADATA_KEY,
                Map.of("policy", "ASSISTANT_PLACEHOLDER"));

        ChatRun created = service(repository, cache).createRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.domain.routing.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                metadata,
                com.huawei.it.ex.one.domain.chat.ChatRunMode.NEXT,
                null,
                null
        ));

        assertThat(created.metadata()).containsEntry("businessKey", "businessValue")
                .doesNotContainKey(AgentDataPersistenceMetadata.RUN_METADATA_KEY);
    }

    @Test
    void createRunningRejectsDeletedSessionBeforeClaimingActiveRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = new ChatRunApplicationService(repository, cache, new InMemoryEventStore(0L),
                new PermissionChecker(), new StatusSessionRepository("DELETED"));

        assertThatThrownBy(() -> service.createRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.domain.routing.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                Map.of(),
                com.huawei.it.ex.one.domain.chat.ChatRunMode.NEXT,
                null,
                null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
        assertThat(cache.getActive("tenant1", "user1", "session1")).isEmpty();
    }

    @Test
    void interactionContinuationDoesNotCreateRunAfterClaimWasReconciled() {
        ClaimLostRunRepository repository = new ClaimLostRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);

        assertThatThrownBy(() -> service.createInteractionRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.domain.routing.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                Map.of("interactionId", "interaction1"),
                com.huawei.it.ex.one.domain.chat.ChatRunMode.NEXT,
                "msg-user",
                "msg-user"
        ), "interaction1"))
                .isInstanceOf(com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException.class);

        assertThat(repository.findById("run2")).isEmpty();
        assertThat(cache.getActive("tenant1", "user1", "session1")).isEmpty();
    }

    private ChatRunApplicationService service(InMemoryRunRepository repository, InMemoryRunCache cache) {
        return new ChatRunApplicationService(repository, cache, new InMemoryEventStore(0L),
                new PermissionChecker(), new FixedSessionRepository());
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private ChatRun runningRun() {
        Instant now = Instant.now();
        return new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, null, null, null,
                now, null, Map.of(), now, now);
    }

    private ChatRun run(String runId, String userId, String runtimeProvider) {
        Instant now = Instant.now();
        return new ChatRun(runId, "tenant1", userId, "session1", ChatRunStatus.COMPLETED,
                "AGENT_RUNTIME", null, runtimeProvider, null, null, null, null,
                now, now, Map.of(), now, now);
    }

    private static class InMemoryRunRepository implements ChatRunRepository {
        private final Map<String, ChatRun> runs = new HashMap<>();
        private ChatRun saved;

        @Override
        public ChatRun save(ChatRun run) {
            saved = run;
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<ChatRun> findById(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId)
                    .filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId()));
        }

        @Override
        public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return runs.values().stream()
                    .filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId()))
                    .filter(run -> sessionId.equals(run.sessionId()))
                    .filter(run -> !run.status().terminal())
                    .findFirst();
        }
    }

    private static class TerminalRaceRunRepository extends InMemoryRunRepository {
        @Override
        public ChatRun save(ChatRun run) {
            if (run.status() == ChatRunStatus.CANCELLING) {
                return super.save(run.completed(9L));
            }
            return super.save(run);
        }
    }

    private static class FailingStopClaimRunRepository extends InMemoryRunRepository {
        @Override
        public boolean tryMarkCancelling(StopClaim claim) {
            throw new IllegalStateException("stop claim db failure");
        }
    }

    private static class ClaimLostRunRepository extends InMemoryRunRepository {
        @Override
        public Optional<ChatRun> insertInteractionContinuationIfClaimed(ChatRun run, String interactionId) {
            return Optional.empty();
        }
    }

    private static class InMemoryRunCache implements ChatRunCache {
        private final Map<String, ChatRun> active = new HashMap<>();
        private final Map<String, Boolean> cancelled = new HashMap<>();

        @Override
        public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) {
            return Optional.ofNullable(active.get(tenantId + ":" + userId + ":" + sessionId));
        }

        @Override
        public boolean tryClaimActive(ChatRun run) {
            String key = run.tenantId() + ":" + run.userId() + ":" + run.sessionId();
            if (active.containsKey(key)) {
                return false;
            }
            active.put(key, run);
            return true;
        }

        @Override
        public void putActive(ChatRun run) {
            active.put(run.tenantId() + ":" + run.userId() + ":" + run.sessionId(), run);
        }

        @Override
        public void evictActive(String tenantId, String userId, String sessionId) {
            active.remove(tenantId + ":" + userId + ":" + sessionId);
        }

        @Override
        public void markCancellationRequested(String runId) {
            cancelled.put(runId, true);
        }

        @Override
        public ChatRunCancelSignal cancellationSignal(String runId) {
            return Boolean.TRUE.equals(cancelled.get(runId))
                    ? ChatRunCancelSignal.REQUESTED
                    : ChatRunCancelSignal.NOT_REQUESTED;
        }
    }

    private static class InMemoryEventStore implements ChatEventStore {
        private final long latestSeq;

        private InMemoryEventStore(long latestSeq) {
            this.latestSeq = latestSeq;
        }

        @Override
        public ChatEvent append(ChatEvent event) {
            return event;
        }

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.it.ex.one.domain.chat.RunExecutionClaim claim) {
            return append(event);
        }

        @Override
        public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) {
            return List.of();
        }

        @Override
        public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq) {
            return List.of();
        }

        @Override
        public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) {
            return latestSeq;
        }
    }

    private static class FixedSessionRepository implements SessionRepository {
        @Override
        public Optional<ChatSession> findById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", "ACTIVE", "web", now, now));
        }

        @Override
        public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
            return List.of();
        }

        @Override
        public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }

        @Override
        public ChatSession save(ChatSession session) {
            return session;
        }
    }

    private static final class StatusSessionRepository extends FixedSessionRepository {
        private final String status;

        private StatusSessionRepository(String status) {
            this.status = status;
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", status, "web", now, now));
        }
    }
}
