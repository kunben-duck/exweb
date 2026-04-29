package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.gateway.AgentBindingCache;
import com.huawei.finance.front.one.application.gateway.AgentBindingRepository;
import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.agent.AgentBindingStatus;
import com.huawei.finance.front.one.domain.agent.AgentBindingType;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentBindingApplicationServiceTest {
    @Test
    void readsCacheBeforeRepository() {
        FakeRepository repository = new FakeRepository();
        FakeCache cache = new FakeCache();
        AgentBinding cached = binding("b_cache", AgentBindingType.SUB_AGENT, AgentBindingStatus.ACTIVE);
        cache.put(cached);
        AgentBindingApplicationService service = service(repository, cache);

        Optional<AgentBinding> found = service.findActive("t", "u", "s");

        assertThat(found).contains(cached);
        assertThat(repository.findActiveCalls).isZero();
    }

    @Test
    void fallsBackToRepositoryAndWarmsCache() {
        FakeRepository repository = new FakeRepository();
        FakeCache cache = new FakeCache();
        AgentBinding persisted = binding("b_db", AgentBindingType.AGENT_RUNTIME, AgentBindingStatus.ACTIVE);
        repository.saved = persisted;
        AgentBindingApplicationService service = service(repository, cache);

        Optional<AgentBinding> found = service.findActive("t", "u", "s");

        assertThat(found).contains(persisted);
        assertThat(cache.get("t", "u", "s")).contains(persisted);
    }

    @Test
    void terminalStatusEvictsCache() {
        FakeRepository repository = new FakeRepository();
        FakeCache cache = new FakeCache();
        AgentBinding active = binding("b1", AgentBindingType.SUB_AGENT, AgentBindingStatus.ACTIVE);
        cache.put(active);
        repository.saved = active;
        AgentBindingApplicationService service = service(repository, cache);

        service.cancelActive("t", "u", "s");

        assertThat(cache.get("t", "u", "s")).isEmpty();
        assertThat(repository.saved.status()).isEqualTo(AgentBindingStatus.CANCELLED);
    }

    @Test
    void requiresUserInputKeepsBindingRoutable() {
        FakeRepository repository = new FakeRepository();
        FakeCache cache = new FakeCache();
        AgentBinding active = binding("b1", AgentBindingType.SUB_AGENT, AgentBindingStatus.ACTIVE);
        repository.saved = active;
        AgentBindingApplicationService service = service(repository, cache);

        boolean observed = service.observeEvent(active, MessageCompletedEvent.of("run", "s", "REQUIRES_USER_INPUT"));

        assertThat(observed).isTrue();
        assertThat(repository.saved.status()).isEqualTo(AgentBindingStatus.REQUIRES_USER_INPUT);
        assertThat(cache.get("t", "u", "s")).isPresent();
    }

    private AgentBindingApplicationService service(FakeRepository repository, FakeCache cache) {
        return new AgentBindingApplicationService(repository, cache, new FakeIdGenerator(), Duration.ofDays(3));
    }

    private AgentBinding binding(String id, AgentBindingType type, AgentBindingStatus status) {
        Instant now = Instant.now();
        return new AgentBinding(id, "t", "u", "s", type, "agent", "provider", null, null,
                status, "run", now.plus(Duration.ofDays(1)), now, now, Map.of());
    }

    private static class FakeRepository implements AgentBindingRepository {
        private AgentBinding saved;
        private int findActiveCalls;

        @Override
        public Optional<AgentBinding> findActive(String tenantId, String userId, String sessionId) {
            findActiveCalls++;
            return Optional.ofNullable(saved);
        }

        @Override
        public AgentBinding save(AgentBinding binding) {
            saved = binding;
            return binding;
        }
    }

    private static class FakeCache implements AgentBindingCache {
        private final Map<String, AgentBinding> store = new HashMap<>();

        @Override
        public Optional<AgentBinding> get(String tenantId, String userId, String sessionId) {
            return Optional.ofNullable(store.get(key(tenantId, userId, sessionId)));
        }

        @Override
        public void put(AgentBinding binding) {
            store.put(key(binding.tenantId(), binding.userId(), binding.chatSessionId()), binding);
        }

        @Override
        public void evict(String tenantId, String userId, String sessionId) {
            store.remove(key(tenantId, userId, sessionId));
        }

        private String key(String tenantId, String userId, String sessionId) {
            return tenantId + ":" + userId + ":" + sessionId;
        }
    }

    private static class FakeIdGenerator implements IdGenerator {
        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_1";
        }
    }
}
