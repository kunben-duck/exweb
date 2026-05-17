package com.huawei.finance.front.one.application.integration.memory;

import java.util.Map;

/**
 * 工作记忆存储端口。
 *
 * <p>工作记忆保存当前会话的结构化临时变量，默认由 Redis 承载，openGauss 不作为事实源。</p>
 */
public interface WorkingMemoryStore {
    /**
     * 加载会话工作变量。
     *
     * @param sessionId 前端聊天会话标识。
     * @return 当前会话工作变量；不存在时返回空 Map。
     */
    Map<String, Object> load(String sessionId);

    /**
     * 合并更新会话工作变量。
     *
     * @param sessionId 前端聊天会话标识。
     * @param variables 需要合并写入的结构化工作变量。
     */
    void update(String sessionId, Map<String, Object> variables);

    /**
     * 清空会话工作变量。
     *
     * @param sessionId 前端聊天会话标识。
     */
    void clear(String sessionId);
}
