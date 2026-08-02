package com.huawei.it.ex.one.application.service.agentdatapersistence;

/** Assistant 历史数据留存策略。 */
public enum AgentDataPersistencePolicy {
    FULL,
    ASSISTANT_PLACEHOLDER;

    /**
     * 同一 run 内只允许从完整保存收紧为占位保存，不能重新放宽。
     */
    public AgentDataPersistencePolicy tighten(AgentDataPersistencePolicy candidate) {
        if (this == ASSISTANT_PLACEHOLDER || candidate == ASSISTANT_PLACEHOLDER) {
            return ASSISTANT_PLACEHOLDER;
        }
        return FULL;
    }
}
