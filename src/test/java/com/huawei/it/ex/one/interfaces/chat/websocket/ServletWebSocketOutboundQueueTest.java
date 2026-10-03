/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServletWebSocketOutboundQueueTest {
    @Test
    void pendingNotificationYieldsMessageCapacityWithoutReorderingRunMessages() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(2, 4096);
        queue.offer(notification(100));
        assertThat(queue.tryStartDraining()).isTrue();
        var first = message("first", 100, false);
        var second = message("second", 200, false);
        queue.offer(first);

        assertThat(queue.offer(second)).isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);
        assertThat(queue.snapshot().queuedBytes()).isEqualTo(300);
        assertThat(queue.snapshot().draining()).isTrue();
        assertThat(queue.poll()).isSameAs(first);
        assertThat(queue.poll()).isSameAs(second);
        assertThat(queue.poll()).isNull();
    }

    @Test
    void pendingNotificationYieldsByteCapacityToControlMessage() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(4, 1024);
        queue.offer(notification(400));
        var reply = new ServletWebSocketOutboundQueue.OutboundMessage("reply", 700, "reply", null, null, false);

        assertThat(queue.offer(reply)).isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);
        assertThat(queue.snapshot().queuedBytes()).isEqualTo(700);
        assertThat(queue.poll()).isSameAs(reply);
    }

    @Test
    void removingNotificationDoesNotDisableRealRunOverflowProtection() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(4, 1024);
        queue.offer(notification(100));
        var first = message("first", 800, false);
        queue.offer(first);

        assertThat(queue.offer(message("large", 800, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.OVERFLOW);
        assertThat(queue.snapshot().queuedBytes()).isEqualTo(800);
        assertThat(queue.poll()).isSameAs(first);
    }

    @Test
    void notificationAlreadyInFlightDoesNotConsumeQueueCapacity() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(1, 1024);
        queue.offer(notification(100));
        queue.tryStartDraining();
        assertThat(queue.poll().notification()).isTrue();
        assertThat(queue.offer(message("run", 1024, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);
        assertThat(queue.snapshot().draining()).isTrue();
    }

    private ServletWebSocketOutboundQueue.OutboundMessage notification(int bytes) {
        return new ServletWebSocketOutboundQueue.OutboundMessage("notice", bytes, "notification", null, null, false);
    }

    @Test
    void notificationsNeverOverflowBusyQueueAndRejectedDrainPreservesRunMessages() {
        ServletWebSocketOutboundQueue queue = new ServletWebSocketOutboundQueue(2, 1024);
        ServletWebSocketOutboundQueue.OutboundMessage notification =
                new ServletWebSocketOutboundQueue.OutboundMessage("notice", 6, "notification", null, null, false);
        assertThat(queue.offer(notification)).isEqualTo(ServletWebSocketOutboundQueue.OfferResult.ACCEPTED);
        assertThat(queue.tryStartDraining()).isTrue();
        assertThat(queue.offer(notification))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.SKIPPED_NOTIFICATION);
        queue.offer(message("stream-item", 100, false));
        assertThat(queue.discardNotificationsAfterRejectedDrain()).isTrue();
        assertThat(queue.snapshot().queueSize()).isEqualTo(1);
        assertThat(queue.snapshot().queuedBytes()).isEqualTo(100);
        assertThat(queue.poll().envelopeType()).isEqualTo("message");
        assertThat(queue.offer(new ServletWebSocketOutboundQueue.OutboundMessage(
                "large", 2048, "notification", null, null, false)))
                .isEqualTo(ServletWebSocketOutboundQueue.OfferResult.SKIPPED_NOTIFICATION);
    }

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
