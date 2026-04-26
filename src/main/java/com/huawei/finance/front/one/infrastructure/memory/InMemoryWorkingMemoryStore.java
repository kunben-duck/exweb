package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.application.gateway.WorkingMemoryStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryWorkingMemoryStore implements WorkingMemoryStore {
    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();
    @Override public Map<String, Object> load(String sessionId) { return new HashMap<>(store.getOrDefault(sessionId, Map.of())); }
    @Override public void update(String sessionId, Map<String, Object> variables) { if (sessionId != null) store.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).putAll(variables); }
    @Override public void clear(String sessionId) { store.remove(sessionId); }
}
