/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService.AdmissionCancellation;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class ChatRunAdmissionCommitServiceTest {
    private static final UserContext USER = new UserContext("tenant1", "user1", "User One");
    private static final IntentExpertScope EXPERT_A =
            new IntentExpertScope("expert-a", "专家A", "expert_a_entry");
    private static final IntentExpertScope EXPERT_B =
            new IntentExpertScope("expert-b", "专家B", "expert_b_entry");

    @Test
    void standardAdmissionUsesScopeFromLockedSessionSnapshot() {
        Fixture fixture = fixture();
        ChatSession stale = session(EXPERT_A);
        ChatSession current = session(EXPERT_B);
        ChatCommand command = command(null, null, null);
        when(fixture.sessionService().lockAndReloadForMessageMutation(
                USER.tenantId(), USER.ownerUserId(), stale)).thenReturn(current);

        ChatRunAdmissionCommitService.AdmissionResult result = fixture.service().commit(
                USER, command, stale, "run1", List.of());

        ArgumentCaptor<CreateChatRunContext> context = ArgumentCaptor.forClass(CreateChatRunContext.class);
        verify(fixture.runService()).insertRunning(context.capture());
        assertThat(IntentExpertContext.fromMetadata(context.getValue().metadata())).contains(EXPERT_B);
        assertThat(result.intentExpertScope()).isEqualTo(EXPERT_B);
        verify(fixture.sessionService()).prepareRunMessage(
                USER, command, current, "run1", List.of());
    }

    @Test
    void explicitTargetAlwaysUsesDirectAdmissionWithoutPreloadedScope() {
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        ChatInteractionApplicationService interactionService = mock(ChatInteractionApplicationService.class);
        ChatRunAdmissionCommitService commitService = mock(ChatRunAdmissionCommitService.class);
        ChatRunAdmissionCoordinator coordinator = new ChatRunAdmissionCoordinator(
                sessionService, runService, interactionService);
        coordinator.setCommitService(commitService);
        ChatRunAdmissionCommitService.AdmissionResult expected =
                new ChatRunAdmissionCommitService.AdmissionResult(mock(ChatRunMessagePlan.class), mock(ChatRun.class));
        when(commitService.commitDirectRuntime(any())).thenReturn(expected);

        ChatRunAdmissionCommitService.AdmissionResult actual = coordinator.admitStandard(
                new ChatRunAdmissionCoordinator.StandardAdmission(
                        USER,
                        command("DOMAIN_AGENT", "skill-b", null),
                        session(null),
                        "run1",
                        List.of(),
                        new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_AGENT, "skill-b"),
                        false));

        assertThat(actual).isSameAs(expected);
        verify(commitService).commitDirectRuntime(any());
        verify(commitService, never()).commit(any(), any(), any(), any(), any());
    }

    @Test
    void intentClarificationUsesScopeFromLockedSessionSnapshot() {
        Fixture fixture = fixture();
        ChatSession stale = session(EXPERT_A);
        ChatSession current = session(EXPERT_B);
        ChatInteractionRequest interaction = mock(ChatInteractionRequest.class);
        when(interaction.interactionType()).thenReturn(ChatInteractionType.INTENT_CLARIFICATION);
        when(interaction.id()).thenReturn("interaction1");
        when(interaction.assistantMessageId()).thenReturn("assistant1");
        when(fixture.sessionService().lockAndReloadForMessageMutation(
                USER.tenantId(), USER.ownerUserId(), stale)).thenReturn(current);
        ChatRunMessagePlan clarificationPlan = messagePlan();
        when(fixture.sessionService().prepareIntentClarificationAnswer(
                eq(USER), eq(current), eq("run1"), eq("assistant1"), eq("answer"), eq(List.of())))
                .thenReturn(clarificationPlan);
        when(fixture.runService().insertInteractionRunning(any(), eq("interaction1")))
                .thenReturn(mock(ChatRun.class));
        when(fixture.interactionService().markAnsweredForRun(interaction, "run1")).thenReturn(1);

        ChatRunAdmissionCommitService.AdmissionResult result = fixture.service().commitIntentClarification(
                new ChatRunAdmissionCommitService.IntentClarificationAdmissionCommand(
                        USER, stale, "run1", interaction, "answer", List.of(), Map.of()));

        ArgumentCaptor<CreateChatRunContext> context = ArgumentCaptor.forClass(CreateChatRunContext.class);
        verify(fixture.runService()).insertInteractionRunning(context.capture(), eq("interaction1"));
        assertThat(IntentExpertContext.fromMetadata(context.getValue().metadata())).contains(EXPERT_B);
        assertThat(result.intentExpertScope()).isEqualTo(EXPERT_B);
    }

    @Test
    void intentExpertSwitchKeepsCancelledBindingsOutOfUnstartedCompensation() {
        Fixture fixture = fixture();
        ChatSession stale = session(EXPERT_A);
        ChatSession current = session(EXPERT_A);
        ChatCommand command = command("INTENT_EXPERT", "expert-b", EXPERT_B);
        AdmissionCancellation cancellation = cancellation();
        when(fixture.sessionService().lockAndReloadForMessageMutation(
                USER.tenantId(), USER.ownerUserId(), stale)).thenReturn(current);
        when(fixture.bindingService().cancelActiveForAdmissionWithSnapshots(
                USER.tenantId(), USER.ownerUserId(), current.id())).thenReturn(List.of(cancellation));

        ChatRunAdmissionCommitService.AdmissionResult result = fixture.service().commitIntentExpert(
                new ChatRunAdmissionCommitService.DirectRuntimeAdmissionCommand(
                        USER, command, stale, "run1", List.of(),
                        new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.INTENT_EXPERT, "expert-b")));

        assertThat(result.cancelledBindings()).containsExactly(cancellation.cancelled());
        assertThat(result.restorableAdmissionCancellations()).isEmpty();
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(fixture.sessionService()).updateMetadataWithoutTouch(eq(current), metadata.capture());
        assertThat(IntentExpertContext.fromSessionMetadata(metadata.getValue())).contains(EXPERT_B);
    }

    @Test
    void directTargetClearsFreshScopeWithoutRestoringItsOldBinding() {
        Fixture fixture = fixture();
        ChatSession stale = session(null);
        ChatSession current = session(EXPERT_B);
        ChatCommand command = command("DOMAIN_AGENT", "skill-b", null);
        AdmissionCancellation cancellation = cancellation();
        when(fixture.sessionService().lockAndReloadForMessageMutation(
                USER.tenantId(), USER.ownerUserId(), stale)).thenReturn(current);
        when(fixture.bindingService().cancelActiveForAdmissionWithSnapshots(
                USER.tenantId(), USER.ownerUserId(), current.id())).thenReturn(List.of(cancellation));

        ChatRunAdmissionCommitService.AdmissionResult result = fixture.service().commitDirectRuntime(
                new ChatRunAdmissionCommitService.DirectRuntimeAdmissionCommand(
                        USER, command, stale, "run1", List.of(),
                        new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_AGENT, "skill-b")));

        assertThat(result.cancelledBindings()).containsExactly(cancellation.cancelled());
        assertThat(result.restorableAdmissionCancellations()).isEmpty();
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(fixture.sessionService()).updateMetadataWithoutTouch(eq(current), metadata.capture());
        assertThat(IntentExpertContext.fromSessionMetadata(metadata.getValue())).isEmpty();
    }

    @Test
    void unscopedDirectTargetRetainsExistingAdmissionCompensation() {
        Fixture fixture = fixture();
        ChatSession session = session(null);
        ChatCommand command = command("DOMAIN_AGENT", "skill-b", null);
        AdmissionCancellation cancellation = cancellation();
        when(fixture.sessionService().lockAndReloadForMessageMutation(
                USER.tenantId(), USER.ownerUserId(), session)).thenReturn(session);
        when(fixture.bindingService().cancelActiveForAdmissionWithSnapshots(
                USER.tenantId(), USER.ownerUserId(), session.id())).thenReturn(List.of(cancellation));

        ChatRunAdmissionCommitService.AdmissionResult result = fixture.service().commitDirectRuntime(
                new ChatRunAdmissionCommitService.DirectRuntimeAdmissionCommand(
                        USER, command, session, "run1", List.of(),
                        new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_AGENT, "skill-b")));

        assertThat(result.cancelledBindingsForCacheSync()).containsExactly(cancellation);
        assertThat(result.restorableAdmissionCancellations()).containsExactly(cancellation);
    }

    private Fixture fixture() {
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        ChatInteractionApplicationService interactionService = mock(ChatInteractionApplicationService.class);
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        ChatRunMessagePlan messagePlan = messagePlan();
        ChatRun run = mock(ChatRun.class);
        when(sessionService.prepareRunMessage(any(), any(), any(), any(), any()))
                .thenReturn(messagePlan);
        when(runService.insertRunning(any())).thenReturn(run);
        return new Fixture(
                new ChatRunAdmissionCommitService(
                        sessionService, runService, interactionService, bindingService),
                sessionService,
                runService,
                interactionService,
                bindingService);
    }

    private ChatRunMessagePlan messagePlan() {
        ChatMessage userMessage = mock(ChatMessage.class);
        when(userMessage.id()).thenReturn("msg1");
        return new ChatRunMessagePlan(ChatRunMode.NEXT, null, userMessage, null);
    }

    private ChatCommand command(String targetType, String targetId, IntentExpertScope scope) {
        return new ChatCommand(
                "cmd1", USER.tenantId(), USER.ownerUserId(), "session1", null, "web", "query",
                List.of(), Map.of(), targetType, targetId, ChatRunMode.NEXT, null, null, null)
                .withIntentExpertScope(scope);
    }

    private ChatSession session(IntentExpertScope scope) {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        return new ChatSession(
                "session1", USER.tenantId(), USER.ownerUserId(), "title", "ACTIVE", "web",
                null, "session1", null, null, 0L,
                IntentExpertContext.replaceSessionMetadata(null, scope), now, now);
    }

    private AdmissionCancellation cancellation() {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        RuntimeBinding previous = new RuntimeBinding(
                "binding-a", USER.tenantId(), USER.ownerUserId(), "session1", "domain-agent",
                "leaf-a", "runtime-a", RuntimeBindingStatus.ACTIVE, "run-old", null,
                now, now, Map.of());
        return new AdmissionCancellation(previous, previous.withStatus(RuntimeBindingStatus.CANCELLED));
    }

    private record Fixture(
            ChatRunAdmissionCommitService service,
            SessionApplicationService sessionService,
            ChatRunApplicationService runService,
            ChatInteractionApplicationService interactionService,
            RuntimeBindingApplicationService bindingService
    ) {
    }
}
