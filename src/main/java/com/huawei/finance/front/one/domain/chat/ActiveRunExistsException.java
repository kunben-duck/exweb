package com.huawei.finance.front.one.domain.chat;

/**
 * 同一会话已经存在仍在执行的 run。
 *
 * <p>当前上线策略是同一 {@code tenantId + userId + sessionId} 同一时刻只允许一个
 * RUNNING/CANCELLING run，避免多页签重复提交导致消息树、activeRun 和 stop 语义混乱。</p>
 */
public class ActiveRunExistsException extends IllegalStateException {
    private final String sessionId;
    private final String activeRunId;

    /**
     * 创建 active run 冲突异常。
     *
     * @param sessionId 冲突发生的会话标识。
     * @param activeRunId 已存在的 active run 标识。
     */
    public ActiveRunExistsException(String sessionId, String activeRunId) {
        super("ACTIVE_RUN_EXISTS: 当前会话已有运行中的回答，请先停止或等待完成。activeRunId=" + activeRunId);
        this.sessionId = sessionId;
        this.activeRunId = activeRunId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String activeRunId() {
        return activeRunId;
    }
}
