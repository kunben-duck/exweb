/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.IntentFeedbackExecutorConfiguration;
import com.huawei.it.ex.one.application.config.IntentFeedbackProperties;
import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackException;
import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackRepository;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceCorrectionRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageInput;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;
import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;
import com.huawei.it.ex.one.infrastructure.intent.DefaultIntentAccessNameResolver;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceHttpProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

class IntentFeedbackApplicationServiceTest {
    private static final UserContext USER = new UserContext("tenant", "user", "user");
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private final ChatRunRepository runs = mock(ChatRunRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final IntentPreferenceCorrectionRepository preferences = mock(IntentPreferenceCorrectionRepository.class);
    private final MemoryFeedbacks feedbacks = new MemoryFeedbacks();
    private final Map<String, IntentPreferenceCorrection> storedPreferences = new HashMap<>();
    private final AtomicInteger preferenceWrites = new AtomicInteger();
    private IntentFeedbackApplicationService service;
    private IntentFeedbackCommitService commits;
    private ThreadPoolTaskExecutor executor;
    private IntentFeedbackTaskDispatcher dispatcher;

    @BeforeEach
    void setup() {
        when(runs.findByTenantIdAndUserIdAndId("tenant", "user", "a")).thenReturn(Optional.of(run("a", "q", Map.of())));
        when(runs.findByTenantIdAndUserIdAndId("tenant", "user", "b")).thenReturn(Optional.of(run("b", "q", Map.of())));
        when(runs.findByTenantIdAndUserIdAndId("tenant", "user", "c")).thenReturn(Optional.of(run("c", "q", Map.of())));
        when(messages.findInputByOwnerAndId("tenant", "user", "q"))
                .thenReturn(Optional.of(new ChatMessageInput("session", "user", "可信原问题")));
        AtomicInteger ids = new AtomicInteger();
        IdGenerator generator = (type, context) -> type + ids.incrementAndGet();
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setAccessName("EX_default");
        properties.setRequestAccessNamePrefix("EX_");
        commits = new IntentFeedbackCommitService(runs, messages, feedbacks, preferences,
                new DefaultIntentAccessNameResolver(properties), generator);
        doAnswer(call -> {
            IntentPreferenceCorrection preference = call.getArgument(0);
            storedPreferences.put(preference.intentAccessName(), preference);
            preferenceWrites.incrementAndGet();
            return null;
        }).when(preferences).upsert(any());
        ProxyFactory factory = new ProxyFactory(commits);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new MemoryTransactions(),
                new AnnotationTransactionAttributeSource()));
        commits = (IntentFeedbackCommitService) factory.getProxy();
        IntentFeedbackProperties settings = new IntentFeedbackProperties();
        executor = new IntentFeedbackExecutorConfiguration().intentFeedbackExecutor(settings);
        dispatcher = new IntentFeedbackTaskDispatcher(executor, settings);
        service = new IntentFeedbackApplicationService(commits, dispatcher);
    }

    @AfterEach
    void shutdownExecutor() {
        executor.shutdown();
    }

    @Test
    void correctCreatesPreferenceUsingLogicalEntryAndLightweightTrustedQuery() {
        IntentFeedback result = service.record(USER, "a", correct(" fin ")).block();
        assertThat(result.intentAccessName()).isEqualTo("fin");
        IntentPreferenceCorrection preference = storedPreferences.get("fin");
        assertThat(preference.queryText()).isEqualTo("可信原问题");
        assertThat(preference.preferenceIntent()).isEqualTo("技能B");
        assertThat(preference.originalIntent()).isNull();
        assertThat(preference.sourceType()).isEqualTo("INTENT_FEEDBACK_CORRECT");
        assertThat(preference.createdAt()).isEqualTo(preference.updatedAt()).isEqualTo(result.createdAt());
        verify(messages, never()).findByOwnerAndId(anyString(), anyString(), anyString());
        verify(runs, never()).save(any());
    }

    @Test
    void commentAndCorrectWithoutTargetDoNotModifyExistingPreference() {
        service.record(USER, "a", correct("fin")).block();
        service.record(USER, "b", new IntentFeedbackCommand(
                "INCORRECT_COMMENT", " 没有合适技能 ", null, null, null, "fin")).block();
        service.record(USER, "c", new IntentFeedbackCommand("CORRECT", null, null, null, null, "fin")).block();
        assertThat(feedbacks.values).hasSize(3);
        assertThat(preferenceWrites).hasValue(1);
        assertThat(feedbacks.values.get("b").commentText()).isEqualTo("没有合适技能");
    }

    @Test
    void retryDoesNotRefreshOrOverwriteLaterPreferenceAndDifferentSubmissionConflicts() {
        IntentFeedback first = service.record(USER, "a", correct("fin")).block();
        service.record(USER, "b", new IntentFeedbackCommand("INCORRECT_SWITCH", null, "c", "skill-c",
                new IntentFeedbackCommand.SelectedIntent("c", "技能C"), "fin")).block();
        assertThat(service.record(USER, "a", correct("fin")).block()).isEqualTo(first);
        assertThat(preferenceWrites).hasValue(2);
        assertThat(storedPreferences.get("fin").preferenceIntent()).isEqualTo("技能C");
        assertThatThrownBy(() -> service.record(USER, "a", correct("other")).block())
                .isInstanceOf(IntentFeedbackException.class)
                .extracting("code").isEqualTo("INTENT_FEEDBACK_ALREADY_SUBMITTED");
    }

    @Test
    void preferenceFailureRollsBackFeedbackAndCanBeRetried() {
        doAnswer(call -> {
            storedPreferences.put("fin", call.getArgument(0));
            throw new IllegalStateException("database failure");
        }).when(preferences).upsert(any());
        assertThatThrownBy(() -> service.record(USER, "a", correct("fin")).block())
                .isInstanceOf(IntentFeedbackException.class)
                .extracting("code").isEqualTo("INTENT_FEEDBACK_UNAVAILABLE");
        assertThat(feedbacks.values).isEmpty();
        assertThat(storedPreferences).isEmpty();
    }

    @Test
    void concurrentSameFeedbackOnlyWritesPreferenceOnce() {
        executor.setMaxPoolSize(2);
        executor.setCorePoolSize(2);
        CompletableFuture<IntentFeedback> first = CompletableFuture.supplyAsync(
                () -> service.record(USER, "a", correct("fin")).block());
        CompletableFuture<IntentFeedback> second = CompletableFuture.supplyAsync(
                () -> service.record(USER, "a", correct("fin")).block());
        assertThat(first.join()).isEqualTo(second.join());
        assertThat(preferenceWrites).hasValue(1);
    }

    @Test
    void duplicateInsertIsComparedAfterTransactionRollback() {
        IntentFeedbackCommitService proxy = mock(IntentFeedbackCommitService.class);
        IntentFeedback winner = new IntentFeedback("fb", "tenant", "user", "session", "a", "q",
                "fin", "CORRECT", null, null, null, "b", "技能B", NOW);
        IntentFeedbackCommand command = correct("fin");
        when(proxy.record(USER, "a", command)).thenThrow(new DuplicateKeyException("race")).thenReturn(winner);
        assertThat(new IntentFeedbackApplicationService(proxy, dispatcher).record(USER, "a", command).block())
                .isEqualTo(winner);
    }

    @Test
    void missingAndForeignRunsAreDeniedWithoutWriting() {
        assertThatThrownBy(() -> service.record(USER, "missing", correct("fin")).block())
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.find(new UserContext("other", "user", "user"), "a").block())
                .isInstanceOf(SecurityException.class);
        assertThat(feedbacks.values).isEmpty();
    }

    @Test
    void replacementMustBelongToSameQuestionAndBeDifferentRun() {
        assertThatThrownBy(() -> service.record(USER, "a", switchTo("a")).block())
                .isInstanceOf(IllegalArgumentException.class);
        when(runs.findByTenantIdAndUserIdAndId("tenant", "user", "b"))
                .thenReturn(Optional.of(run("b", "other-question", Map.of())));
        assertThatThrownBy(() -> service.record(USER, "a", switchTo("b")).block())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(feedbacks.values).isEmpty();
    }

    @Test
    void sourceExpertScopeOverridesFrontendAndDefaultIsNotPrefixed() {
        when(runs.findByTenantIdAndUserIdAndId("tenant", "user", "a")).thenReturn(Optional.of(run("a", "q",
                IntentExpertContext.withScope(Map.of(), new IntentExpertScope("tax", "Tax", "tax_entry")))));
        assertThat(service.record(USER, "a", correct("fin")).block().intentAccessName()).isEqualTo("tax_entry");
        assertThat(service.record(USER, "b", correct(null)).block().intentAccessName()).isEqualTo("EX_default");
        assertThat(storedPreferences).containsOnlyKeys("tax_entry", "EX_default");
    }

    @Test
    void schedulerRejectionReturnsUnavailableWithoutAccessingStorage() {
        executor.shutdown();
        assertThatThrownBy(() -> service.record(USER, "a", correct("fin")).block())
                .isInstanceOf(IntentFeedbackException.class);
        assertThat(feedbacks.values).isEmpty();
    }

    @Test
    void validatesExclusiveFieldsAndUnicodeBoundaries() {
        String emoji = "\uD83D\uDE00".repeat(1024);
        assertThat(new IntentFeedbackCommand("INCORRECT_COMMENT", emoji, null, null, null, null).commentText())
                .isEqualTo(emoji);
        assertThatThrownBy(() -> new IntentFeedbackCommand("INCORRECT_COMMENT", emoji + "a", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentFeedbackCommand("INCORRECT_COMMENT", " ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentFeedbackCommand("CORRECT", "bad", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentFeedbackCommand("INCORRECT_SWITCH", null, "b", "skill", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private IntentFeedbackCommand correct(String entry) {
        return new IntentFeedbackCommand("CORRECT", null, null, null,
                new IntentFeedbackCommand.SelectedIntent("b", " 技能B "), entry);
    }

    private IntentFeedbackCommand switchTo(String runId) {
        return new IntentFeedbackCommand("INCORRECT_SWITCH", null, runId, "skill-b",
                new IntentFeedbackCommand.SelectedIntent("b", "技能B"), "fin");
    }

    private ChatRun run(String id, String message, Map<String, Object> metadata) {
        return new ChatRun(id, "tenant", "user", "session", ChatRunStatus.RUNNING, "DOMAIN_AGENT", "skill",
                "domain-agent", null, ChatRunMode.REGENERATE_ASSISTANT, message, message, null, 1L, 1L,
                null, NOW, null, metadata, NOW.plusSeconds(id.charAt(0) - 'a'), NOW);
    }

    private static class MemoryFeedbacks implements IntentFeedbackRepository {
        private final Map<String, IntentFeedback> values = new HashMap<>();

        @Override
        public void insert(IntentFeedback value) {
            if (values.putIfAbsent(value.runId(), value) != null) {
                throw new DuplicateKeyException("owner run");
            }
        }

        @Override
        public Optional<IntentFeedback> findByOwnerAndRun(String tenant, String user, String run) {
            return Optional.ofNullable(values.get(run));
        }

        @Override
        public List<IntentFeedback> findByOwnerAndRuns(String tenant, String user, List<String> ids) {
            return List.of();
        }
    }

    // Transaction-interceptor regression model, not an openGauss integration test.
    private class MemoryTransactions extends AbstractPlatformTransactionManager {
        private final ReentrantLock lock = new ReentrantLock();

        @Override
        protected Object doGetTransaction() {
            return new HashMap<String, Object>();
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            lock.lock();
            assertThat(definition.getTimeout()).isEqualTo(2);
            Map<String, Object> snapshot = (Map<String, Object>) transaction;
            snapshot.put("feedback", new HashMap<>(feedbacks.values));
            snapshot.put("preference", new HashMap<>(storedPreferences));
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void doRollback(DefaultTransactionStatus status) {
            Map<String, Object> snapshot = (Map<String, Object>) status.getTransaction();
            feedbacks.values.clear();
            feedbacks.values.putAll((Map<String, IntentFeedback>) snapshot.get("feedback"));
            storedPreferences.clear();
            storedPreferences.putAll((Map<String, IntentPreferenceCorrection>) snapshot.get("preference"));
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            lock.unlock();
        }
    }
}
