package com.huawei.it.ex.one.application.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
class RuntimeBindingApplicationServiceTest {
    @Test
    void relayBindingDoesNotRecordAgentMode() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        RuntimeBindingApplicationService service = service(repository, new InMemoryRuntimeBindingCache());

        RuntimeBinding created = service.resolveForRun("t", "u", "s", "run1", "leaf1").binding();

        assertThat(created.provider()).isEqualTo(RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER);
        assertThat(created.metadata()).doesNotContainKey("agentMode");
    }

    @Test
    void newDomainAgentBindingDoesNotInheritModeFromCancelledActiveBinding() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        RuntimeBinding active = binding(RuntimeBindingStatus.ACTIVE).withMetadata(
                AgentModeBindingContext.apply(Map.of(), mode("thinking_level", "3")));
        repository.saved = active;
        RuntimeBindingApplicationService service = service(repository, new InMemoryRuntimeBindingCache());

        RuntimeBinding selected = service.bindDomainAgentForRun(new DomainAgentBindingCommand(
                "t", "u", "s", "run2", "leaf2", "fund-agent", "intent", Map.of()));

        assertThat(selected.provider()).isEqualTo(RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        assertThat(AgentModeBindingContext.fromBinding(selected)).isNull();
    }

    @Test
    void domainAgentBindingRecordsExplicitModeAndActiveBindingSupportsReplaceAndClear() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        RuntimeBindingApplicationService service = service(repository, new InMemoryRuntimeBindingCache());

        RuntimeBinding created = service.bindDomainAgentForRun(new DomainAgentBindingCommand(
                "t", "u", "s", "run1", "leaf1", "fund-agent", "intent", Map.of(),
                mode("thinking", "deep")));
        RuntimeBinding unchanged = service.touchDomainAgentForRun(created, "run2", null);
        RuntimeBinding replaced = service.touchDomainAgentForRun(
                unchanged, "run3", mode("execution", "long_task"));
        RuntimeBinding cleared = service.touchDomainAgentForRun(replaced, "run4", AgentModeProfile.empty());

        assertThat(AgentModeBindingContext.fromBinding(created)).isEqualTo(mode("thinking", "deep"));
        assertThat(AgentModeBindingContext.fromBinding(unchanged)).isEqualTo(mode("thinking", "deep"));
        assertThat(AgentModeBindingContext.fromBinding(replaced)).isEqualTo(mode("execution", "long_task"));
        assertThat(cleared.metadata()).doesNotContainKey("agentMode");
    }
    @Test
    void readsCacheBeforeRepository() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE);
        cache.put(binding);
        RuntimeBindingApplicationService service = service(repository, cache);

        Optional<RuntimeBinding> found = service.findActive("t", "u", "s");

        assertThat(found).contains(binding);
        assertThat(repository.findActiveCalls).isZero();
    }

    @Test
    void missFallsBackToRepositoryAndWarmsCache() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE);
        repository.saved = binding;
        RuntimeBindingApplicationService service = service(repository, cache);

        Optional<RuntimeBinding> found = service.findActive("t", "u", "s");

        assertThat(found).contains(binding);
        assertThat(cache.get("t", "u", "s")).contains(binding);
    }

    @Test
    void evictsExpiredCacheBeforeRepositoryFallback() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        cache.put(expiredBinding());
        RuntimeBindingApplicationService service = service(repository, cache);

        Optional<RuntimeBinding> found = service.findActive("t", "u", "s");

        assertThat(found).isEmpty();
        assertThat(repository.findActiveCalls).isEqualTo(1);
        assertThat(cache.get("t", "u", "s")).isEmpty();
    }

    @Test
    void admissionCancellationUpdatesDatabaseWithoutTouchingCache() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding active = binding(RuntimeBindingStatus.ACTIVE);
        repository.saved = active;
        cache.put(active);
        RuntimeBindingApplicationService service = service(repository, cache);

        List<RuntimeBinding> cancelled = service.cancelActiveForAdmission("t", "u", "s");

        assertThat(cancelled).singleElement()
                .extracting(RuntimeBinding::status)
                .isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(repository.saved.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(cache.get("t", "u", "s")).contains(active);
    }

    @Test
    void admissionCancellationPreservesResumableRelayBinding() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding resumable = binding(RuntimeBindingStatus.RESUMABLE);
        repository.saved = resumable;
        RuntimeBindingApplicationService service = service(repository, cache);

        assertThat(service.cancelActiveForAdmission("t", "u", "s")).isEmpty();
        assertThat(repository.saved).isSameAs(resumable);
    }

    @Test
    void createsRelayRuntimeBinding() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBinding binding = service.create("t", "u", "s", "run1");

        assertThat(binding.provider()).isEqualTo(RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER);
        assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(binding.runtimeSessionId()).isEqualTo("s");
        assertThat(cache.get("t", "u", "s")).contains(binding);
    }

    @Test
    void missingZeroOrNegativeTtlCreatesNonExpiringBinding() {
        InMemoryRuntimeBindingRepository zeroRepository = new InMemoryRuntimeBindingRepository();
        RuntimeBindingApplicationService zeroTtl = service(zeroRepository,
                new InMemoryRuntimeBindingCache(), Duration.ZERO);
        RuntimeBindingApplicationService negativeTtl = service(new InMemoryRuntimeBindingRepository(),
                new InMemoryRuntimeBindingCache(), Duration.ofSeconds(-1));
        RuntimeBindingApplicationService missingTtl = service(new InMemoryRuntimeBindingRepository(),
                new InMemoryRuntimeBindingCache(), null);

        assertThat(zeroTtl.create("t", "u", "s", "run-zero").expiresAt()).isNull();
        assertThat(negativeTtl.create("t", "u", "s", "run-negative").expiresAt()).isNull();
        assertThat(missingTtl.create("t", "u", "s", "run-missing").expiresAt()).isNull();
    }

    @Test
    void positiveTtlKeepsDomainAgentSlidingExpiry() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        RuntimeBindingApplicationService service = service(repository, new InMemoryRuntimeBindingCache(),
                Duration.ofDays(3));

        RuntimeBinding binding = service.bindDomainAgentForRun(new DomainAgentBindingCommand(
                "t", "u", "s", "run1", "leaf1", "skill1", "intent", Map.of()));

        assertThat(binding.expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(2)));
    }

    @Test
    void resolveForRunCreatesNewSessionWhenNoActiveBindingExists() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBindingResolution resolution = service.resolveForRun("t", "u", "s", "run1", "leaf1");

        assertThat(resolution.sessionMode()).isEqualTo(RuntimeSessionMode.NEW);
        assertThat(resolution.binding().runtimeSessionId()).isEqualTo("s");
        assertThat(resolution.binding().leafMessageId()).isEqualTo("leaf1");
    }

    @Test
    void resolveForRunReusesSessionBindingEvenWhenLeafChanges() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding existing = binding(RuntimeBindingStatus.ACTIVE).withRuntimeSessionId("runtime-1");
        repository.saved = existing;
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBindingResolution resolution = service.resolveForRun("t", "u", "s", "run2", "leaf2");

        assertThat(resolution.sessionMode()).isEqualTo(RuntimeSessionMode.RESUME);
        assertThat(resolution.binding().runtimeSessionId()).isEqualTo("runtime-1");
    }

    @Test
    void resolveForRunReactivatesCompletedRelaySessionWithoutExpiry() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        Instant old = Instant.now().minus(Duration.ofDays(30));
        repository.saved = new RuntimeBinding("binding1", "t", "u", "s", "relay",
                "leaf1", "runtime-1", RuntimeBindingStatus.RESUMABLE, "run1",
                null, old, old, Map.of("runtimeSessionEstablished", true));
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBindingResolution resolution = service.resolveForRun("t", "u", "s", "run2", "leaf2");

        assertThat(resolution.sessionMode()).isEqualTo(RuntimeSessionMode.RESUME);
        assertThat(resolution.binding().id()).isEqualTo("binding1");
        assertThat(resolution.binding().runtimeSessionId()).isEqualTo("runtime-1");
        assertThat(resolution.binding().status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(resolution.binding().expiresAt()).isNull();
        assertThat(resolution.binding().lastRunId()).isEqualTo("run2");
        assertThat(resolution.binding().leafMessageId()).isEqualTo("leaf2");
    }

    @Test
    void completedRelayBindingBecomesResumableAndKeepsActualSessionId() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding active = binding(RuntimeBindingStatus.ACTIVE).withRuntimeSessionId("runtime-1");
        repository.saved = active;
        cache.put(active);
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBinding completed = service.completeAfterRun(active, "run2", "leaf2");

        assertThat(completed.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
        assertThat(completed.runtimeSessionId()).isEqualTo("runtime-1");
        assertThat(completed.lastRunId()).isEqualTo("run2");
        assertThat(completed.leafMessageId()).isEqualTo("leaf2");
        assertThat(completed.expiresAt()).isNull();
        assertThat(cache.get("t", "u", "s")).isEmpty();
    }

    @Test
    void deletingSessionCancelsResumableRelayBinding() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        repository.saved = binding(RuntimeBindingStatus.RESUMABLE)
                .withRuntimeSessionId("runtime-1")
                .withExpiresAt(null);
        RuntimeBindingApplicationService service = service(repository, cache);

        service.cancelAllForSession("t", "u", "s");

        assertThat(repository.saved.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
    }

    @Test
    void ignoresCachedBindingFromDifferentRuntimeProvider() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        cache.put(binding("previous-runtime", RuntimeBindingStatus.ACTIVE));
        RuntimeBindingApplicationService service = service(repository, cache);

        Optional<RuntimeBinding> found = service.findActive("t", "u", "s");

        assertThat(found).isEmpty();
        assertThat(cache.get("t", "u", "s")).isEmpty();
        assertThat(repository.findActiveCalls).isEqualTo(1);
    }

    @Test
    void repositoryLookupUsesCurrentRuntimeProvider() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        repository.saved = binding("another-runtime", RuntimeBindingStatus.ACTIVE);
        RuntimeBindingApplicationService service = service(repository, cache);

        Optional<RuntimeBinding> found = service.findActive("t", "u", "s");

        assertThat(found).isEmpty();
        assertThat(cache.get("t", "u", "s")).isEmpty();
    }

    @Test
    void cancelActiveRemovesHotCache() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE);
        repository.saved = binding;
        cache.put(binding);
        RuntimeBindingApplicationService service = service(repository, cache);

        service.cancelActive("t", "u", "s");

        assertThat(repository.saved.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(cache.get("t", "u", "s")).isEmpty();
    }

    @Test
    void observesRuntimeSessionIdFromEvent() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE);
        repository.saved = binding;
        RuntimeBindingApplicationService service = service(repository, cache);

        service.observeEvent(binding, MessageCompletedEvent.of("run", "s", Map.of("runtimeSessionId", "runtime-1")));

        assertThat(repository.saved.runtimeSessionId()).isEqualTo("runtime-1");
    }

    @Test
    void unchangedRuntimeSessionIdStillMarksRelaySessionEstablished() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE).withRuntimeSessionId("runtime-1");
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBinding observed = service.observeEvent(binding,
                MessageCompletedEvent.of("run", "s", Map.of("runtimeSessionId", "runtime-1")));

        assertThat(observed.runtimeSessionId()).isEqualTo("runtime-1");
        assertThat(observed.expiresAt()).isNull();
        assertThat(observed.metadata()).containsEntry("runtimeSessionEstablished", true);
        assertThat(repository.saved).isEqualTo(observed);
    }

    @Test
    void touchAndMoveToLeafRefreshesBindingEvenWhenLeafIsUnchanged() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        Instant now = Instant.now();
        RuntimeBinding binding = new RuntimeBinding("binding1", "t", "u", "s", "relay",
                "leaf1", "runtime-1", RuntimeBindingStatus.ACTIVE, "run1",
                now.plus(Duration.ofMinutes(1)), now, now, Map.of());
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBinding refreshed = service.touchAndMoveToLeaf(binding, "run2", "leaf1");

        assertThat(refreshed.id()).isEqualTo("binding1");
        assertThat(refreshed.leafMessageId()).isEqualTo("leaf1");
        assertThat(refreshed.lastRunId()).isEqualTo("run2");
        assertThat(refreshed.expiresAt()).isAfter(binding.expiresAt());
        assertThat(repository.saved).isEqualTo(refreshed);
    }

    @Test
    void resumeForInteractionRefreshesOriginalBinding() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE).withRuntimeSessionId("runtime-old");
        repository.saved = binding;
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBinding resumed = service.resumeForInteraction(interactionRequest("runtime-new"), "run-interaction");

        assertThat(resumed.id()).isEqualTo(binding.id());
        assertThat(resumed.lastRunId()).isEqualTo("run-interaction");
        assertThat(resumed.leafMessageId()).isEqualTo("msg-assistant");
        assertThat(resumed.runtimeSessionId()).isEqualTo("runtime-new");
        assertThat(resumed.expiresAt()).isNull();
        assertThat(resumed.metadata()).containsEntry("runtimeSessionEstablished", true);
    }

    @Test
    void resumeForInteractionAppliesExplicitAgentModeSnapshot() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        RuntimeBinding binding = binding("domain-agent", RuntimeBindingStatus.ACTIVE).withMetadata(
                AgentModeBindingContext.apply(Map.of(), mode("thinking", "fast")));
        repository.saved = binding;
        RuntimeBindingApplicationService service = service(repository, new InMemoryRuntimeBindingCache());

        RuntimeBinding replaced = service.resumeForInteraction(
                interactionRequest(binding.runtimeSessionId()), "run-replaced", mode("execution", "long_task"));
        RuntimeBinding cleared = service.resumeForInteraction(
                interactionRequest(binding.runtimeSessionId()), "run-cleared", AgentModeProfile.empty());

        assertThat(AgentModeBindingContext.fromBinding(replaced))
                .isEqualTo(mode("execution", "long_task"));
        assertThat(cleared.metadata()).doesNotContainKey("agentMode");
    }

    @Test
    void relayInteractionIgnoresExplicitAgentModeSnapshot() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE);
        repository.saved = binding;
        RuntimeBindingApplicationService service = service(repository, new InMemoryRuntimeBindingCache());

        RuntimeBinding resumed = service.resumeForInteraction(
                interactionRequest(binding.runtimeSessionId()), "run-interaction", mode("thinking", "deep"));

        assertThat(resumed.metadata()).doesNotContainKey("agentMode");
    }

    @Test
    void loadsCancelledExpiredDomainAgentBindingForRefusalRerouteWithoutReactivatingIt() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        Instant now = Instant.now();
        RuntimeBinding binding = new RuntimeBinding("binding1", "t", "u", "s", "domain-agent",
                "leaf1", "domain-session-1", RuntimeBindingStatus.CANCELLED, "run1",
                now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofDays(1)), now,
                Map.of("domainAgentId", "agent-a", "routeSource", "intent-agent"));
        repository.saved = binding;
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBinding loaded = service.loadDomainAgentForReroute(
                "t", "u", "s", "binding1", "agent-a");

        assertThat(loaded).isEqualTo(binding);
        assertThat(loaded.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(loaded.expiresAt()).isBefore(Instant.now());
        assertThat(cache.get("t", "u", "s")).isEmpty();
        assertThat(repository.saved).isEqualTo(binding);
    }

    @Test
    void rejectsRefusalRerouteBindingFromDifferentDomainAgent() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        RuntimeBinding binding = binding("domain-agent", RuntimeBindingStatus.CANCELLED)
                .withMetadata(Map.of("domainAgentId", "agent-a", "routeSource", "intent-agent"));
        repository.saved = binding;
        RuntimeBindingApplicationService service = service(repository, new InMemoryRuntimeBindingCache());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.loadDomainAgentForReroute(
                        "t", "u", "s", "binding1", "agent-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent 已变化");
    }

    private RuntimeBindingApplicationService service(InMemoryRuntimeBindingRepository repository,
                                                    InMemoryRuntimeBindingCache cache) {
        return service(repository, cache, Duration.ofDays(3));
    }

    private AgentModeProfile mode(String scheme, String code) {
        return new AgentModeProfile(List.of(new AgentModeSelection(scheme, code, null)));
    }

    private RuntimeBindingApplicationService service(InMemoryRuntimeBindingRepository repository,
                                                      InMemoryRuntimeBindingCache cache, Duration ttl) {
        return new RuntimeBindingApplicationService(repository, cache, new FixedIdGenerator(), ttl, "relay");
    }

    private RuntimeBinding binding(RuntimeBindingStatus status) {
        return binding("relay", status);
    }

    private RuntimeBinding binding(String provider, RuntimeBindingStatus status) {
        Instant now = Instant.now();
        return new RuntimeBinding("binding1", "t", "u", "s", provider,
                null, status, "run", now.plus(Duration.ofDays(1)), now, now, Map.of());
    }

    private RuntimeBinding expiredBinding() {
        Instant now = Instant.now();
        return new RuntimeBinding("binding1", "t", "u", "s", "relay",
                null, RuntimeBindingStatus.ACTIVE, "run", now.minus(Duration.ofMinutes(1)), now, now, Map.of());
    }

    private ChatInteractionRequest interactionRequest(String runtimeSessionId) {
        Instant now = Instant.now();
        return new ChatInteractionRequest("interaction1", "t", "u", "s", "run-source", null,
                "msg-user", "msg-assistant", "relay", "binding1", runtimeSessionId, "approval1",
                ChatInteractionType.CLARIFICATION, ChatInteractionStatus.WAITING, Map.of("approval_id", "approval1"),
                Map.of(), null, null, null, now, now);
    }

    private static class InMemoryRuntimeBindingRepository implements RuntimeBindingRepository {
        private RuntimeBinding saved;
        private int findActiveCalls;

        @Override
        public Optional<RuntimeBinding> findById(String bindingId) {
            return Optional.ofNullable(saved).filter(binding -> binding.id().equals(bindingId));
        }

        @Override
        public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider) {
            findActiveCalls++;
            return Optional.ofNullable(saved)
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.routableAt(Instant.now()));
        }

        @Override
        public List<RuntimeBinding> findResumableBySession(String tenantId, String userId, String sessionId,
                                                           String provider) {
            return Optional.ofNullable(saved)
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.RESUMABLE)
                    .stream()
                    .toList();
        }

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            saved = binding;
            return binding;
        }
    }

    private static class InMemoryRuntimeBindingCache implements RuntimeBindingCache {
        private final Map<String, RuntimeBinding> active = new HashMap<>();

        @Override
        public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId) {
            return Optional.ofNullable(active.get(key(tenantId, userId, sessionId)));
        }

        @Override
        public void put(RuntimeBinding binding) {
            active.put(key(binding.tenantId(), binding.userId(), binding.chatSessionId()), binding);
        }

        @Override
        public void evict(String tenantId, String userId, String sessionId) {
            active.remove(key(tenantId, userId, sessionId));
        }

        private String key(String tenantId, String userId, String sessionId) {
            return tenantId + ":" + userId + ":" + sessionId;
        }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_1";
        }
    }
}
