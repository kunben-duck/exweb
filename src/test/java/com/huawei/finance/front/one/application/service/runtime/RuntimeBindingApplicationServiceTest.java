package com.huawei.finance.front.one.application.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import com.huawei.finance.front.one.domain.runtime.RuntimeBindingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
class RuntimeBindingApplicationServiceTest {
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
    void ignoresUnchangedRuntimeSessionId() {
        InMemoryRuntimeBindingRepository repository = new InMemoryRuntimeBindingRepository();
        InMemoryRuntimeBindingCache cache = new InMemoryRuntimeBindingCache();
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE).withRuntimeSessionId("runtime-1");
        RuntimeBindingApplicationService service = service(repository, cache);

        RuntimeBinding observed = service.observeEvent(binding,
                MessageCompletedEvent.of("run", "s", Map.of("runtimeSessionId", "runtime-1")));

        assertThat(observed).isEqualTo(binding);
        assertThat(repository.saved).isNull();
    }

    private RuntimeBindingApplicationService service(InMemoryRuntimeBindingRepository repository,
                                                    InMemoryRuntimeBindingCache cache) {
        return new RuntimeBindingApplicationService(repository, cache, new FixedIdGenerator(), Duration.ofDays(3), "relay");
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

    private static class InMemoryRuntimeBindingRepository implements RuntimeBindingRepository {
        private RuntimeBinding saved;
        private int findActiveCalls;

        @Override
        public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider) {
            findActiveCalls++;
            return Optional.ofNullable(saved).filter(binding -> provider.equals(binding.provider()));
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
