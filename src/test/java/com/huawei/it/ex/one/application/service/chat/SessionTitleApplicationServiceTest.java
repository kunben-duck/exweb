/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleAppExclusionProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleRequest;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class SessionTitleApplicationServiceTest {
    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final ChatRunRepository runRepository = mock(ChatRunRepository.class);
    private final List<SessionTitleRequest> requests = new ArrayList<>();
    private final Map<String, List<ChatMessage>> paths = new LinkedHashMap<>();
    private final Map<String, ChatRun> runs = new LinkedHashMap<>();
    private final AtomicReference<String> generatedTitle = new AtomicReference<>();

    private SessionTitleProperties properties;
    private SessionTitleMetadata metadata;
    private AtomicReference<ChatSession> session;
    private SessionTitleApplicationService service;

    @BeforeEach
    void setUp() {
        properties = new SessionTitleProperties();
        properties.setEnabled(true);
        generatedTitle.set(" 经营\n情况   分析 ");
        metadata = new SessionTitleMetadata(new ObjectMapper(), properties);
        session = new AtomicReference<>(session(metadata.initialize(null, SessionTitleSummarySource.AUTO)));

        when(sessionRepository.findByTenantIdAndUserIdAndId(anyString(), anyString(), anyString()))
                .thenAnswer(ignored -> Optional.ofNullable(session.get()));
        doNothing().when(sessionRepository).lockForMessageMutation(anyString(), anyString(), anyString());
        when(sessionRepository.updateTitleWithoutTouch(
                org.mockito.ArgumentMatchers.any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    ChatSession current = invocation.getArgument(0);
                    ChatSession updated = copySession(
                            current, invocation.getArgument(1), invocation.getArgument(2));
                    session.set(updated);
                    return updated;
                });
        when(messageRepository.findPathNodesToMessage(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> paths.getOrDefault(invocation.getArgument(3), List.of()));
        when(runRepository.findByTenantIdAndUserIdAndIds(anyString(), anyString(), anyCollection()))
                .thenAnswer(invocation -> runsFor(invocation.getArgument(2)));

        SessionTitleProvider provider = request -> {
            requests.add(request);
            return Mono.just(generatedTitle.get());
        };
        service = serviceWith(provider);
    }

    @Test
    void firstThreeBusinessQuestionsAreSentInPathOrderAndFourthIsSkipped() {
        List<ChatMessage> path = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            ChatRun run = run("run-" + index, ChatRunMode.NEXT, Map.of());
            ChatMessage message = message("message-" + index, "run-" + index, "问题" + index, index);
            runs.put(run.id(), run);
            path.add(message);
            paths.put(message.id(), List.copyOf(path));
            service.schedule(user(), command("问题" + index, ChatRunMode.NEXT, "zh_CN"), session.get(),
                    new ChatRunMessagePlan(ChatRunMode.NEXT, message.parentMessageId(), message, null), run);
        }

        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).queries()).containsExactly("问题1");
        assertThat(requests.get(1).queries()).containsExactly("问题1", "问题2");
        assertThat(requests.get(2).queries()).containsExactly("问题1", "问题2", "问题3");
        assertThat(requests.get(2).language()).isEqualTo("zh_CN");
        assertThat(session.get().title()).isEqualTo("经营 情况 分析");
        assertThat(metadata.read(session.get().metadataJson()))
                .contains(new SessionTitleSummaryState(SessionTitleSummarySource.AUTO, 3, 3L));
    }

    @Test
    void laterQuestionsRetryFirstThreeQueriesUntilSummarySucceeds() {
        List<SessionTitleRequest> attempts = new ArrayList<>();
        SessionTitleProvider provider = request -> {
            attempts.add(request);
            if (attempts.size() < 5) {
                return Mono.error(new IllegalStateException("title service unavailable"));
            }
            return Mono.just("晚轮补偿标题");
        };
        service = serviceWith(provider);

        scheduleConversation(6);

        assertThat(attempts).hasSize(5);
        assertThat(attempts.get(2).queries()).containsExactly("问题1", "问题2", "问题3");
        assertThat(attempts.get(3).queries()).containsExactly("问题1", "问题2", "问题3");
        assertThat(attempts.get(4).queries()).containsExactly("问题1", "问题2", "问题3");
        assertThat(session.get().title()).isEqualTo("晚轮补偿标题");
        assertThat(metadata.read(session.get().metadataJson()))
                .contains(new SessionTitleSummaryState(SessionTitleSummarySource.AUTO, 3, 5L));
    }

    @Test
    void laterQuestionCompletesSummaryWhenThirdAttemptFailed() {
        List<SessionTitleRequest> attempts = new ArrayList<>();
        SessionTitleProvider provider = request -> {
            attempts.add(request);
            if (attempts.size() == 3) {
                return Mono.error(new IllegalStateException("third summary failed"));
            }
            return Mono.just("标题" + attempts.size());
        };
        service = serviceWith(provider);

        scheduleConversation(5);

        assertThat(attempts).hasSize(4);
        assertThat(attempts.get(3).queries()).containsExactly("问题1", "问题2", "问题3");
        assertThat(session.get().title()).isEqualTo("标题4");
        assertThat(metadata.read(session.get().metadataJson()))
                .contains(new SessionTitleSummaryState(SessionTitleSummarySource.AUTO, 3, 4L));
    }

    @Test
    void clarificationMessagesAreExcludedAndDuplicateQuestionsArePreserved() {
        ChatRun firstRun = run("run-1", ChatRunMode.NEXT, Map.of());
        ChatRun clarificationRun = run("run-clarify", ChatRunMode.NEXT, Map.of("interactionId", "interaction-1"));
        ChatRun secondRun = run("run-2", ChatRunMode.NEXT, Map.of());
        ChatMessage first = message("message-1", firstRun.id(), "重复问题", 1);
        ChatMessage clarification = message("message-clarify", clarificationRun.id(), "澄清回答", 2);
        ChatMessage second = message("message-2", secondRun.id(), "重复问题", 3);
        runs.put(firstRun.id(), firstRun);
        runs.put(clarificationRun.id(), clarificationRun);
        runs.put(secondRun.id(), secondRun);
        paths.put(second.id(), List.of(first, clarification, second));

        service.schedule(user(), command("重复问题", ChatRunMode.NEXT, null), session.get(),
                new ChatRunMessagePlan(ChatRunMode.NEXT, clarification.id(), second, null), secondRun);

        assertThat(requests).singleElement()
                .extracting(SessionTitleRequest::queries)
                .isEqualTo(List.of("重复问题", "重复问题"));
        assertThat(requests.getFirst().language()).isEqualTo("zh_CN");
    }

    @Test
    void editingAnEarlierQuestionUsesTheNewerNodeOrderEvenWhenPathGetsShorter() {
        session.set(session(metadata.markAuto(session.get().metadataJson(), 3, 6L)));
        ChatRun editedRun = run("run-edit", ChatRunMode.EDIT_USER, Map.of());
        ChatMessage edited = message("message-edit", editedRun.id(), "修改后的第一问", 10L);
        runs.put(editedRun.id(), editedRun);
        paths.put(edited.id(), List.of(edited));

        service.schedule(user(), command("修改后的第一问", ChatRunMode.EDIT_USER, null), session.get(),
                new ChatRunMessagePlan(ChatRunMode.EDIT_USER, null, edited, null), editedRun);

        assertThat(requests).singleElement()
                .extracting(SessionTitleRequest::queries)
                .isEqualTo(List.of("修改后的第一问"));
        assertThat(metadata.read(session.get().metadataJson()))
                .contains(new SessionTitleSummaryState(SessionTitleSummarySource.AUTO, 1, 10L));
    }

    @Test
    void protectedTitlesNeverInvokeProviderForLateQuestions() {
        List<ChatMessage> path = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            ChatRun pathRun = run("run-" + index, ChatRunMode.NEXT, Map.of());
            ChatMessage pathMessage = message("message-" + index, pathRun.id(), "问题" + index, index);
            runs.put(pathRun.id(), pathRun);
            path.add(pathMessage);
        }
        ChatRun run = runs.get("run-4");
        ChatMessage message = path.getLast();
        paths.put(message.id(), List.copyOf(path));

        session.set(session(metadata.initialize(null, SessionTitleSummarySource.USER)));
        service.schedule(user(), command(message.content(), ChatRunMode.NEXT, null), session.get(),
                new ChatRunMessagePlan(ChatRunMode.NEXT, null, message, null), run);
        session.set(session(metadata.initialize(null, SessionTitleSummarySource.LOCKED)));
        service.schedule(user(), command(message.content(), ChatRunMode.NEXT, null), session.get(),
                new ChatRunMessagePlan(ChatRunMode.NEXT, null, message, null), run);
        session.set(session(null));
        service.schedule(user(), command(message.content(), ChatRunMode.NEXT, null), session.get(),
                new ChatRunMessagePlan(ChatRunMode.NEXT, null, message, null), run);

        assertThat(requests).isEmpty();
    }

    @Test
    void excludedAppIdSkipsSchedulingBeforeRepositoryOrProviderWork() {
        AtomicReference<String> checkedAppId = new AtomicReference<>();
        AtomicInteger exclusionChecks = new AtomicInteger();
        SessionTitleProvider titleProvider = mock(SessionTitleProvider.class);
        service = serviceWith(appId -> {
            checkedAppId.set(appId);
            exclusionChecks.incrementAndGet();
            return Mono.just(true);
        }, titleProvider);
        session.set(session(metadata.initialize(null, SessionTitleSummarySource.AUTO), "app-disabled"));
        for (int index = 1; index <= 6; index++) {
            ChatRun run = run("run-excluded-" + index, ChatRunMode.NEXT, Map.of());
            ChatMessage message = message("message-excluded-" + index, run.id(), "问题" + index, index);
            service.schedule(user(), command(message.content(), ChatRunMode.NEXT, null), session.get(),
                    new ChatRunMessagePlan(ChatRunMode.NEXT, null, message, null), run);
        }

        assertThat(checkedAppId).hasValue("app-disabled");
        assertThat(exclusionChecks).hasValue(6);
        assertThat(requests).isEmpty();
        verifyNoInteractions(messageRepository, runRepository, titleProvider);
    }

    @Test
    void appIdExclusionIsCaseSensitiveAndMainSiteSessionRemainsEligible() {
        List<String> checkedAppIds = new ArrayList<>();
        service = serviceWith(appId -> {
            checkedAppIds.add(appId);
            return Mono.just("app-disabled".equals(appId));
        }, recordingTitleProvider());

        session.set(session(metadata.initialize(null, SessionTitleSummarySource.AUTO), "APP-DISABLED"));
        scheduleQuestion("case-sensitive", 1L);
        assertThat(requests).hasSize(1);

        session.set(session(metadata.initialize(null, SessionTitleSummarySource.AUTO), null));
        scheduleQuestion("main-site", 2L);
        assertThat(requests).hasSize(2);
        assertThat(checkedAppIds).containsExactly("APP-DISABLED", null);
    }

    @Test
    void emptyAndFailedExclusionChecksContinueTitleSummary() {
        List<SessionTitleAppExclusionProvider> providers = List.of(
                appId -> Mono.empty(),
                appId -> Mono.error(new IllegalStateException("async exclusion failure")),
                appId -> {
                    throw new IllegalStateException("sync exclusion failure");
                });
        long nodeOrder = 1L;
        for (SessionTitleAppExclusionProvider exclusionProvider : providers) {
            session.set(session(metadata.initialize(null, SessionTitleSummarySource.AUTO), "app-a"));
            service = serviceWith(exclusionProvider, recordingTitleProvider());
            scheduleQuestion("exclusion-fallback-" + nodeOrder, nodeOrder);
            nodeOrder++;
        }

        assertThat(requests).hasSize(3);
    }

    @Test
    void titleTruncationUsesUnicodeCodePoints() {
        properties.setMaxTitleLength(2);
        generatedTitle.set("\uD83D\uDE00\u8D22\u52A1");
        ChatRun run = run("run-1", ChatRunMode.NEXT, Map.of());
        ChatMessage message = message("message-1", run.id(), "问题", 1);
        runs.put(run.id(), run);
        paths.put(message.id(), List.of(message));

        service.schedule(user(), command("问题", ChatRunMode.NEXT, null), session.get(),
                new ChatRunMessagePlan(ChatRunMode.NEXT, null, message, null), run);

        assertThat(session.get().title()).isEqualTo("\uD83D\uDE00\u8D22");
    }

    @Test
    void limitsConcurrentProviderCallsAndReleasesPermitsAfterCompletion() {
        properties.setMaxConcurrentRequests(2);
        Sinks.One<String> response = Sinks.one();
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        SessionTitleProvider provider = request -> Mono.defer(() -> {
            subscriptions.incrementAndGet();
            int current = inFlight.incrementAndGet();
            peakInFlight.accumulateAndGet(current, Math::max);
            return response.asMono().doFinally(ignored -> inFlight.decrementAndGet());
        });
        service = serviceWith(provider);

        scheduleQuestion("one", 1L);
        scheduleQuestion("two", 2L);
        scheduleQuestion("three", 3L);

        assertThat(subscriptions).hasValue(2);
        assertThat(peakInFlight).hasValue(2);

        response.tryEmitValue("并发标题");
        assertThat(inFlight).hasValue(0);
        scheduleQuestion("after-release", 4L);

        assertThat(subscriptions).hasValue(3);
    }

    @Test
    void releasesPermitWhenProviderFailsSynchronously() {
        properties.setMaxConcurrentRequests(1);
        AtomicInteger subscriptions = new AtomicInteger();
        SessionTitleProvider provider = request -> {
            if (subscriptions.incrementAndGet() == 1) {
                throw new IllegalStateException("provider failed before returning a publisher");
            }
            return Mono.just("恢复后的标题");
        };
        service = serviceWith(provider);

        scheduleQuestion("failed", 1L);
        scheduleQuestion("retry", 2L);

        assertThat(subscriptions).hasValue(2);
        assertThat(session.get().title()).isEqualTo("恢复后的标题");
    }

    @Test
    void releasesPermitAfterApplicationLevelTimeout() throws InterruptedException {
        properties.setMaxConcurrentRequests(1);
        properties.setTimeout("20ms");
        AtomicInteger subscriptions = new AtomicInteger();
        CountDownLatch cancelled = new CountDownLatch(1);
        SessionTitleProvider provider = request -> {
            if (subscriptions.incrementAndGet() == 1) {
                return Mono.<String>never().doFinally(ignored -> cancelled.countDown());
            }
            return Mono.just("超时后的标题");
        };
        service = serviceWith(provider);

        scheduleQuestion("timeout", 1L);
        assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
        for (int attempt = 0; attempt < 20 && subscriptions.get() < 2; attempt++) {
            scheduleQuestion("after-timeout-" + attempt, 10L + attempt);
            Thread.sleep(10L);
        }

        assertThat(subscriptions).hasValue(2);
        assertThat(session.get().title()).isEqualTo("超时后的标题");
    }

    @Test
    void applicationTimeoutAllowsTitleAfterFortyFiveSeconds() {
        properties.setTimeout("60s");
        service = serviceWith(request -> Mono.delay(Duration.ofSeconds(45)).thenReturn("慢模型标题"));

        StepVerifier.withVirtualTime(() -> Mono.fromRunnable(() -> scheduleQuestion("slow-model", 1L))
                        .then(Mono.delay(Duration.ofSeconds(45))))
                .thenAwait(Duration.ofSeconds(30))
                .then(() -> assertThat(session.get().title()).isEqualTo("初始标题"))
                .thenAwait(Duration.ofSeconds(15)).expectNext(0L).verifyComplete();

        assertThat(session.get().title()).isEqualTo("慢模型标题");
    }

    @Test
    void sixtySecondApplicationTimeoutKeepsTitleAndReleasesPermit() {
        properties.setTimeout("60s");
        properties.setMaxConcurrentRequests(1);
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        service = serviceWith(request -> subscriptions.incrementAndGet() == 1
                ? Mono.<String>never().doOnCancel(cancellations::incrementAndGet)
                : Mono.just("下一次标题"));

        StepVerifier.withVirtualTime(() -> Mono.fromRunnable(() -> {
                    scheduleQuestion("timeout", 1L);
                    scheduleQuestion("capacity-full", 2L);
                }).then(Mono.delay(Duration.ofSeconds(60))))
                .thenAwait(Duration.ofSeconds(59))
                .then(() -> {
                    assertThat(cancellations).hasValue(0);
                    assertThat(subscriptions).hasValue(1);
                })
                .thenAwait(Duration.ofSeconds(1)).expectNext(0L).verifyComplete();

        assertThat(cancellations).hasValue(1);
        assertThat(session.get().title()).isEqualTo("初始标题");
        scheduleQuestion("after-timeout", 3L);
        assertThat(subscriptions).hasValue(2);
        assertThat(session.get().title()).isEqualTo("下一次标题");
    }

    @Test
    void rejectsInvalidConcurrencyWhenFeatureIsEnabled() {
        properties.setMaxConcurrentRequests(65);

        assertThatThrownBy(() -> serviceWith(request -> Mono.just("标题")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-requests");
    }

    @Test
    void failuresBeforeSubscriptionDoNotEscapeSchedule() {
        SessionTitleProvider provider = mock(SessionTitleProvider.class);
        metadata = mock(SessionTitleMetadata.class);
        when(metadata.read(anyString())).thenThrow(new IllegalStateException("trigger failed"));
        service = serviceWith(provider);
        assertThatCode(() -> scheduleQuestion("trigger-failure", 1)).doesNotThrowAnyException();

        metadata = new SessionTitleMetadata(new ObjectMapper(), properties);
        properties = spy(properties);
        doThrow(new IllegalStateException("context failed")).when(properties).normalizeLanguage(null);
        service = serviceWith(provider);
        assertThatCode(() -> scheduleQuestion("context-failure", 2)).doesNotThrowAnyException();
        verifyNoInteractions(provider, sessionRepository, messageRepository, runRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"candidate", "model", "commit"})
    void backgroundFailuresKeepTitleAndDoNotEscapeSchedule(String phase) {
        SessionTitleProvider provider = request -> Mono.error(new IllegalStateException("model failed"));
        if ("candidate".equals(phase)) {
            when(sessionRepository.findByTenantIdAndUserIdAndId(anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("database unavailable"));
        } else if ("commit".equals(phase)) {
            provider = request -> Mono.just("new title");
            doThrow(new IllegalStateException("commit failed")).when(sessionRepository)
                    .lockForMessageMutation(anyString(), anyString(), anyString());
        }
        service = serviceWith(provider);

        assertThatCode(() -> scheduleQuestion("failure", 1)).doesNotThrowAnyException();

        assertThat(session.get().title()).isEqualTo("初始标题");
    }

    @Test
    void boundedQueueRejectionDoesNotExecuteWorkOnCaller() throws InterruptedException {
        Scheduler scheduler = Schedulers.newBoundedElastic(1, 1, "title-rejection-test");
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionTitleProvider provider = mock(SessionTitleProvider.class);
        try {
            scheduler.schedule(() -> {
                occupied.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(occupied.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.schedule(() -> { });
            service = serviceWith(appId -> Mono.just(false), provider, scheduler);

            assertThatCode(() -> scheduleQuestion("queue-full", 1)).doesNotThrowAnyException();
            verifyNoInteractions(provider, sessionRepository, messageRepository, runRepository);
        } finally {
            release.countDown();
            scheduler.dispose();
        }
    }

    @Test
    void databaseReadRunsOffCallerAndScheduleReturnsWhileItIsBlocked() throws InterruptedException {
        Scheduler scheduler = Schedulers.newBoundedElastic(1, 4, "title-isolation-test");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> databaseThread = new AtomicReference<>();
        when(sessionRepository.findByTenantIdAndUserIdAndId(anyString(), anyString(), anyString()))
                .thenAnswer(ignored -> {
                    databaseThread.set(Thread.currentThread());
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    finished.countDown();
                    return Optional.empty();
                });
        SessionTitleProvider provider = mock(SessionTitleProvider.class);
        service = serviceWith(appId -> Mono.just(false), provider, scheduler);
        try {
            scheduleQuestion("slow-db", 1);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(databaseThread.get()).isNotSameAs(caller);
            assertThat(finished.getCount()).isEqualTo(1);
            verifyNoInteractions(provider);
        } finally {
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.dispose();
        }
        verify(sessionRepository).findByTenantIdAndUserIdAndId("tenant-1", "user-1", "session-1");
    }

    private SessionTitleApplicationService serviceWith(SessionTitleProvider provider) {
        return serviceWith(appId -> Mono.just(false), provider);
    }

    private SessionTitleApplicationService serviceWith(
            SessionTitleAppExclusionProvider exclusionProvider,
            SessionTitleProvider provider) {
        return serviceWith(exclusionProvider, provider, Schedulers.immediate());
    }

    private SessionTitleApplicationService serviceWith(
            SessionTitleAppExclusionProvider exclusionProvider,
            SessionTitleProvider provider,
            Scheduler scheduler) {
        return new SessionTitleApplicationService(
                properties,
                exclusionProvider,
                provider,
                new SessionTitleCommitService(sessionRepository, metadata),
                metadata,
                sessionRepository,
                messageRepository,
                runRepository,
                scheduler);
    }

    private SessionTitleProvider recordingTitleProvider() {
        return request -> {
            requests.add(request);
            return Mono.just(generatedTitle.get());
        };
    }

    private void scheduleQuestion(String suffix, long nodeOrder) {
        ChatRun run = run("run-" + suffix, ChatRunMode.NEXT, Map.of());
        ChatMessage message = message("message-" + suffix, run.id(), "问题-" + suffix, nodeOrder);
        runs.put(run.id(), run);
        paths.put(message.id(), List.of(message));
        service.schedule(user(), command(message.content(), ChatRunMode.NEXT, null), session.get(),
                new ChatRunMessagePlan(ChatRunMode.NEXT, null, message, null), run);
    }

    private void scheduleConversation(int questionCount) {
        List<ChatMessage> path = new ArrayList<>();
        for (int index = 1; index <= questionCount; index++) {
            ChatRun run = run("run-" + index, ChatRunMode.NEXT, Map.of());
            ChatMessage message = message("message-" + index, run.id(), "问题" + index, index);
            runs.put(run.id(), run);
            path.add(message);
            paths.put(message.id(), List.copyOf(path));
            service.schedule(user(), command(message.content(), ChatRunMode.NEXT, null), session.get(),
                    new ChatRunMessagePlan(ChatRunMode.NEXT, message.parentMessageId(), message, null), run);
        }
    }

    private List<ChatRun> runsFor(Collection<String> ids) {
        return ids.stream().map(runs::get).filter(java.util.Objects::nonNull).toList();
    }

    private UserContext user() {
        return new UserContext("tenant-1", "user-1", "account-1");
    }

    private ChatCommand command(String message, ChatRunMode mode, String language) {
        return new ChatCommand(
                "command-1", "tenant-1", "user-1", "session-1", null, "web", message,
                List.of(), Map.of(), null, null, mode, null, null, null, null, null,
                null, null, Map.of(), null, null, null, null, language);
    }

    private ChatMessage message(String id, String runId, String content, long nodeOrder) {
        return new ChatMessage(
                id, "tenant-1", "user-1", "session-1", nodeOrder == 1 ? null : "previous",
                nodeOrder, (int) nodeOrder - 1, 1, "user", content, null, runId, "NORMAL", false,
                null, null, null, null, null, Instant.parse("2026-08-03T00:00:00Z"));
    }

    private ChatRun run(String id, ChatRunMode mode, Map<String, Object> runMetadata) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        return new ChatRun(
                id, "tenant-1", "user-1", "session-1", ChatRunStatus.RUNNING,
                null, null, null, null, mode, null, "message", null,
                null, null, null, now, null, runMetadata, now, now);
    }

    private ChatSession session(String metadataJson) {
        return session(metadataJson, null);
    }

    private ChatSession session(String metadataJson, String appId) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        return new ChatSession(
                "session-1", "tenant-1", "user-1", "初始标题", "ACTIVE", "web",
                appId, appId == null ? null : "应用", null, "session-1", null, null, 0L, 0L, 0L,
                metadataJson, now, now);
    }

    private ChatSession copySession(ChatSession current, String title, String metadataJson) {
        return new ChatSession(
                current.id(), current.tenantId(), current.userId(), title, current.status(), current.channel(),
                current.appId(), current.appName(), current.currentLeafMessageId(), current.rootSessionId(),
                current.branchSourceSessionId(), current.branchSourceMessageId(), current.lastNodeOrder(),
                current.latestMessageSeq(), current.lastReadSeq(), metadataJson,
                current.createdAt(), current.updatedAt());
    }
}
