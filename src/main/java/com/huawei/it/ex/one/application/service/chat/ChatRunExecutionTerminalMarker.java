/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;

/** Preserves the event-to-execution terminal status mapping. */
final class ChatRunExecutionTerminalMarker {
    private final ChatRunLeaseApplicationService chatRunLeaseService;

    ChatRunExecutionTerminalMarker(ChatRunLeaseApplicationService chatRunLeaseService) {
        this.chatRunLeaseService = chatRunLeaseService;
    }

    void markIfTerminal(ChatEvent event) {
        ChatRunExecutionStatus terminalStatus = switch (event.type()) {
            case "run.completed" -> ChatRunExecutionStatus.COMPLETED;
            case "run.waiting_user" -> ChatRunExecutionStatus.WAITING_USER;
            case "run.failed" -> ChatRunExecutionStatus.FAILED;
            case "run.cancelled" -> ChatRunExecutionStatus.CANCELLED;
            default -> null;
        };
        if (terminalStatus != null) {
            chatRunLeaseService.markTerminal(event.runId(), terminalStatus);
        }
    }
}
