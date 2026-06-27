package com.huawei.finance.front.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServletWebSocketOutboundQueueTest {
    @Test
    void skipsHeartbeatWhenDrainIsBusy() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(4, 4096);

        assertThat(queue.offer(message("stream-item", 100, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);
        assertThat(queue.tryStartDraining()).isTrue();

        assertThat(queue.offer(message("heartbeat", 100, true)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.SKIPPED_HEARTBEAT);
    }

    @Test
    void rejectsWhenMessageCapacityIsExceeded() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(1, 4096);

        assertThat(queue.offer(message("stream-item", 100, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);

        assertThat(queue.offer(message("stream-item", 100, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.OVERFLOW);
        assertThat(queue.snapshot().queueSize()).isEqualTo(1);
    }

    @Test
    void rejectsWhenByteCapacityIsExceeded() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(4, 1024);

        assertThat(queue.offer(message("stream-item", 800, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);

        assertThat(queue.offer(message("stream-item", 800, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.OVERFLOW);
        assertThat(queue.snapshot().queuedBytes()).isEqualTo(800);
    }

    @Test
    void finishDrainRequestsRescheduleWhenMessagesArrivedDuringSend() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(4, 4096);
        assertThat(queue.offer(message("stream-item", 100, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);
        assertThat(queue.tryStartDraining()).isTrue();
        assertThat(queue.poll()).isNotNull();
        assertThat(queue.offer(message("stream-item", 100, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);

        assertThat(queue.finishDrainingAndHasPending()).isTrue();
        assertThat(queue.tryStartDraining()).isTrue();
    }

    @Test
    void closeClearsPendingMessagesAndRejectsFutureOffers() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(4, 4096);
        queue.offer(message("stream-item", 100, false));

        assertThat(queue.close()).isTrue();

        assertThat(queue.snapshot().queueSize()).isZero();
        assertThat(queue.offer(message("stream-item", 100, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.CLOSED);
    }

    private ServletWebSocketOutboundQueue.OutboundMessage message(String type, int bytes, boolean heartbeat) {
        return new ServletWebSocketOutboundQueue.OutboundMessage("x".repeat(bytes), bytes, "message",
                "chat-run-run1", "1", heartbeat);
    }
}
