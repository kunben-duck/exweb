package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;

import reactor.core.publisher.Flux;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

class RunStopHandoffCoordinatorTest {
    private final RuntimeStreamLimitsProperties properties = new RuntimeStreamLimitsProperties();
    private final RunStopControlBus controlBus = mock(RunStopControlBus.class);
    private final RunStopOwnerCoordinator ownerCoordinator = mock(RunStopOwnerCoordinator.class);
    private final ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
    private final ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final UserContext user = new UserContext("tenant1", "user1", "User One");

    @BeforeEach
    void setUp() {
        properties.setStopOwnerHandoffTimeout(Duration.ofSeconds(1));
        when(leaseService.currentInstanceId()).thenReturn("instance-a");
        when(idGenerator.newId(anyString(), any())).thenReturn("stop-1", "stop-2");
    }

    @Test
    void returnsCommittedAfterRemoteOwnerAcceptsAndFinalizes() {
        ChatRun run = run();
        ChatRunExecution execution = execution("instance-b", 7L);
        when(leaseService.findExecution(run.id())).thenReturn(Optional.of(execution));
        when(controlBus.send(any())).thenReturn(new RunStopControlBus.Delivery(1L, Flux.just(
                response("stop-1", "instance-b", RunStopControlBus.Status.ACCEPTED),
                response("stop-1", "instance-b", RunStopControlBus.Status.COMMITTED))));
        AtomicInteger accepted = new AtomicInteger();

        RunStopHandoffCoordinator.Outcome outcome = coordinator().handoff(
                user, run, "USER_STOP", accepted::incrementAndGet);

        assertThat(outcome.committed()).isTrue();
        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.rerouted()).isFalse();
        assertThat(accepted).hasValue(1);
    }

    @Test
    void reroutesOnceWhenExecutionOwnerChanges() {
        ChatRun run = run();
        ChatRunExecution first = execution("instance-b", 7L);
        ChatRunExecution second = execution("instance-c", 8L);
        when(leaseService.findExecution(run.id()))
                .thenReturn(Optional.of(first), Optional.of(second));
        when(runService.requireOwnedRun(user, run.id())).thenReturn(run);
        when(controlBus.send(any())).thenAnswer(invocation -> {
            RunStopControlBus.Request request = invocation.getArgument(0);
            if ("instance-b".equals(request.ownerInstanceId())) {
                return new RunStopControlBus.Delivery(1L, Flux.just(
                        response(request.requestId(), "instance-b", RunStopControlBus.Status.NOT_OWNER)));
            }
            return new RunStopControlBus.Delivery(1L, Flux.just(
                    response(request.requestId(), "instance-c", RunStopControlBus.Status.ACCEPTED),
                    response(request.requestId(), "instance-c", RunStopControlBus.Status.COMMITTED)));
        });
        AtomicInteger accepted = new AtomicInteger();

        RunStopHandoffCoordinator.Outcome outcome = coordinator().handoff(
                user, run, "USER_STOP", accepted::incrementAndGet);

        assertThat(outcome.committed()).isTrue();
        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.rerouted()).isTrue();
        assertThat(accepted).hasValue(1);
    }

    @Test
    void fallsBackImmediatelyWhenStaleOwnerHasNoSubscriber() {
        ChatRun run = run();
        ChatRunExecution execution = execution("instance-b", 7L);
        when(leaseService.findExecution(run.id())).thenReturn(Optional.of(execution));
        when(runService.requireOwnedRun(user, run.id())).thenReturn(run);
        when(controlBus.send(any())).thenAnswer(invocation -> {
            RunStopControlBus.Request request = invocation.getArgument(0);
            return new RunStopControlBus.Delivery(0L, Flux.just(
                    response(request.requestId(), request.ownerInstanceId(),
                            RunStopControlBus.Status.UNAVAILABLE)));
        });
        AtomicInteger accepted = new AtomicInteger();

        RunStopHandoffCoordinator.Outcome outcome = coordinator().handoff(
                user, run, "USER_STOP", accepted::incrementAndGet);

        assertThat(outcome.committed()).isFalse();
        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rerouted()).isFalse();
        assertThat(accepted).hasValue(0);
    }

    private RunStopHandoffCoordinator coordinator() {
        return new RunStopHandoffCoordinator(
                properties, controlBus, ownerCoordinator, leaseService, runService, idGenerator);
    }

    private RunStopControlBus.Response response(
            String requestId,
            String ownerInstanceId,
            RunStopControlBus.Status status) {
        return new RunStopControlBus.Response(
                requestId, "run-1", "instance-a", ownerInstanceId, status,
                status == RunStopControlBus.Status.COMMITTED ? "CANCELLED" : "CANCELLING", null);
    }

    private ChatRun run() {
        Instant now = Instant.now();
        return new ChatRun(
                "run-1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", "relay-session", 1L, 2L,
                null, now, null, Map.of(), now, now);
    }

    private ChatRunExecution execution(String owner, long fencingToken) {
        Instant now = Instant.now();
        return new ChatRunExecution(
                "execution-1", "run-1", "tenant1", "user1", "session1",
                ChatRunExecutionStatus.RUNNING, owner, now, now.plusSeconds(30), fencingToken,
                null, null, 0, null, null, Map.of(), now, now);
    }
}
