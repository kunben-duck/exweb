package com.huawei.it.ex.one.chat.interfaces.websocket;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Servlet WebSocket 单连接出站队列。
 *
 * <p>Servlet {@code WebSocketSession#sendMessage(...)} 是阻塞调用。该队列把业务线程上的
 * envelope 生产与 socket 写出解耦，并确保同一连接任意时刻只有一个 drain 任务在写底层连接。</p>
 */
final class ServletWebSocketOutboundQueue {
    private final Queue<OutboundMessage> messages = new ArrayDeque<>();
    private final int maxMessages;
    private final long maxBytes;
    private long queuedBytes;
    private boolean draining;
    private boolean closed;

    ServletWebSocketOutboundQueue(int maxMessages, long maxBytes) {
        this.maxMessages = Math.max(1, maxMessages);
        this.maxBytes = Math.max(1024L, maxBytes);
    }

    synchronized OfferResult offer(OutboundMessage message) {
        if (closed) {
            return OfferResult.CLOSED;
        }
        if (message.heartbeat() && (draining || !messages.isEmpty())) {
            return OfferResult.SKIPPED_HEARTBEAT;
        }
        if (messages.size() >= maxMessages || queuedBytes + message.bytes() > maxBytes) {
            return OfferResult.OVERFLOW;
        }
        messages.add(message);
        queuedBytes += message.bytes();
        return OfferResult.ACCEPTED;
    }

    synchronized boolean tryStartDraining() {
        if (closed || draining || messages.isEmpty()) {
            return false;
        }
        draining = true;
        return true;
    }

    synchronized OutboundMessage poll() {
        OutboundMessage message = messages.poll();
        if (message != null) {
            queuedBytes = Math.max(0L, queuedBytes - message.bytes());
        }
        return message;
    }

    synchronized boolean finishDrainingAndHasPending() {
        draining = false;
        return !closed && !messages.isEmpty();
    }

    synchronized boolean close() {
        if (closed) {
            return false;
        }
        closed = true;
        messages.clear();
        queuedBytes = 0L;
        return true;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(messages.size(), queuedBytes, draining, closed);
    }

    record OutboundMessage(String payload, int bytes, String envelopeType, String topicId,
                           String offset, boolean heartbeat) {
    }

    record Snapshot(int queueSize, long queuedBytes, boolean draining, boolean closed) {
    }

    enum OfferResult {
        ACCEPTED,
        SKIPPED_HEARTBEAT,
        OVERFLOW,
        CLOSED
    }
}
