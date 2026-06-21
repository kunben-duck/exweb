package com.huawei.finance.front.one.application.service.chat;

/**
 * 实时事件流需要客户端恢复的内部异常。
 *
 * <p>慢客户端、live buffer 溢出或上游实时订阅异常时，WebSocket 不能继续盲目投递更高 seq。
 * 抛出该异常后协议层会返回 {@code RECOVER_REQUIRED}，要求前端通过 run event resume 补齐。</p>
 */
public class StreamRecoveryRequiredException extends RuntimeException {
    private final String topicId;
    private final long afterSeq;
    private final long actualSeq;
    private final String reason;

    public StreamRecoveryRequiredException(String topicId, long afterSeq, String message) {
        this(topicId, afterSeq, afterSeq, "LIVE_SOURCE_ERROR", message);
    }

    public StreamRecoveryRequiredException(String topicId, long afterSeq, long actualSeq, String reason, String message) {
        super(message);
        this.topicId = topicId;
        this.afterSeq = afterSeq;
        this.actualSeq = actualSeq;
        this.reason = reason;
    }

    public String topicId() {
        return topicId;
    }

    public long afterSeq() {
        return afterSeq;
    }

    public long actualSeq() {
        return actualSeq;
    }

    public String reason() {
        return reason;
    }
}
