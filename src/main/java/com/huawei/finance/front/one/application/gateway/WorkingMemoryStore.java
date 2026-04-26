package com.huawei.finance.front.one.application.gateway;

import java.util.Map;

public interface WorkingMemoryStore {
    Map<String, Object> load(String sessionId);
    void update(String sessionId, Map<String, Object> variables);
    void clear(String sessionId);
}
