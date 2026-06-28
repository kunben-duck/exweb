package com.huawei.finance.front.one.application.integration.conversation;

/**
 * 跨实例实时总线发现 run topic 存在实时缺口。
 *
 * <p>该异常只表达 live fanout 层的恢复诉求。协议层会转换为 WebSocket {@code RECOVER_REQUIRED}，
 * run Event Resume 则使用数据库事实源从 {@code recoveryAfterSeq} 之后补齐。</p>
 */
public class ChatLiveRecoveryRequiredException extends RuntimeException {
    private final String topicId;
    private final long recoveryAfterSeq;
    private final long actualSeq;
    private final String reason;

    public ChatLiveRecoveryRequiredException(String topicId, long recoveryAfterSeq, long actualSeq,
                                             String reason, String message) {
        super(message);
        this.topicId = topicId;
        this.recoveryAfterSeq = Math.max(0L, recoveryAfterSeq);
        this.actualSeq = Math.max(0L, actualSeq);
        this.reason = reason == null || reason.isBlank() ? "LIVE_BUS_RECOVERY_REQUIRED" : reason;
    }

    public String topicId() {
        return topicId;
    }

    public long recoveryAfterSeq() {
        return recoveryAfterSeq;
    }

    public long actualSeq() {
        return actualSeq;
    }

    public String reason() {
        return reason;
    }
}
