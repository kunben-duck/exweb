/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DomainAgentAttachmentCountFlowTest extends ChatFlowTestSupport {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directAndIntentCountRejectionDefersBindingThenNextRunUsesIt(boolean direct) throws Exception {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        BlockingCompletionStore events = new BlockingCompletionStore();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        AtomicInteger intentCalls = new AtomicInteger();
        AtomicInteger agentCalls = new AtomicInteger();
        AtomicInteger configurationCalls = new AtomicInteger();
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events,
                repeatedDomainAgentRouteService(intentCalls, "skill-1"), client(agentCalls), noopRuntime(),
                bindings, new DomainAgentProperties(), liveEventBus(), new InMemoryInteractionRequestRepository(),
                runtimeBindingCache(), null, documentFacade(), new InMemoryExecutionRepository(),
                attachmentValidationGate(skill -> {
                    configurationCalls.incrementAndGet();
                    return ".pdf";
                }, 1));
        UserContext user = new UserContext("tenant1", "user1", "User One");
        List<AttachmentRef> documents = List.of(
                new AttachmentRef("doc-1", null, null, null),
                new AttachmentRef("doc-2", null, null, null));
        var execution = service.executeRun(user, command(null, documents, direct)).collectList().toFuture();
        try {
            assertThat(events.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(agentCalls).hasValue(0);
            assertThat(bindings.savedHistory).isEmpty();
            assertThat(events.events).noneMatch(event -> "run.completed".equals(event.type()));
        } finally {
            events.release.countDown();
        }

        List<ChatEvent> result = execution.get(10, TimeUnit.SECONDS);
        assertThat(result).extracting(ChatEvent::type)
                .endsWith("runtime.progress", "runtime.card", "message.completed", "run.completed");
        assertThat(result).noneMatch(event -> "message.delta".equals(event.type()) || "run.failed".equals(event.type()));
        assertThat(configurationCalls).hasValue(1);
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(bindings.saved.metadata()).containsEntry("domainAgentId", "skill-1");
        String bindingId = bindings.saved.id();
        String sessionId = result.getFirst().sessionId();
        String runId = result.getFirst().runId();
        assertThat(messages.parts).filteredOn(part -> "domain-agent-attachment-validation".equals(part.sourceType()))
                .extracting(part -> part.partType()).containsExactly("PROGRESS", "CARD");
        assertThat(events.findByOwnerAndRunAfterSeq("tenant1", "user1", sessionId, runId, 0))
                .filteredOn(event -> "runtime.card".equals(event.type()))
                .singleElement().satisfies(event -> assertThat(event.payload())
                        .containsEntry("code", "DOMAIN_AGENT_ATTACHMENT_COUNT_EXCEEDED")
                        .containsEntry("actualAttachmentCount", 2).containsEntry("maxAttachmentCount", 1));

        // 已完成的数量拒绝留下合法 Binding；再次超限仍不调用 Runtime，也不重复 Intent。
        List<ChatEvent> second = service.executeRun(user, command(sessionId, documents, false))
                .collectList().block(Duration.ofSeconds(10));
        assertThat(second.getLast().type()).isEqualTo("run.completed");
        assertThat(bindings.saved.id()).isEqualTo(bindingId);
        assertThat(agentCalls).hasValue(0);
        assertThat(intentCalls).hasValue(direct ? 0 : 1);

        // 缩小为合法数量后，沿用同一 Binding 正常调用，数量检查没有禁用类型/Runtime链路。
        List<ChatEvent> third = service.executeRun(user, command(sessionId, documents.subList(0, 1), false))
                .collectList().block(Duration.ofSeconds(10));
        assertThat(third.getLast().type()).isEqualTo("run.completed");
        assertThat(agentCalls).hasValue(1);
        assertThat(configurationCalls).hasValue(3);
        assertThat(bindings.saved.id()).isEqualTo(bindingId);
        assertThat(intentCalls).hasValue(direct ? 0 : 1);
    }

    private ChatCommand command(String sessionId, List<AttachmentRef> attachments, boolean direct) {
        return new ChatCommand(null, null, null, sessionId, null, "web", "分析附件", attachments, Map.of(),
                direct ? "DOMAIN_AGENT" : null, direct ? "skill-1" : null,
                ChatRunMode.NEXT, null, null, null);
    }

    private DomainAgentClient client(AtomicInteger calls) {
        return new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                calls.incrementAndGet();
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "answer"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
    }

    private static final class BlockingCompletionStore extends InMemoryEventStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ChatEvent append(ChatEvent event) {
            if ("run.completed".equals(event.type())) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test completion not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return super.append(event);
        }
    }
}
