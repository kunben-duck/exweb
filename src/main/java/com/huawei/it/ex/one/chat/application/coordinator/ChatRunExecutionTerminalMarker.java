package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.service.ChatRunLeaseApplicationService;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus;
import org.springframework.stereotype.Component;

/** Preserves the existing event-to-execution terminal status mapping. */
@Component
public class ChatRunExecutionTerminalMarker {
    private final ChatRunLeaseApplicationService chatRunLeaseService;

    public ChatRunExecutionTerminalMarker(ChatRunLeaseApplicationService chatRunLeaseService) {
        this.chatRunLeaseService = chatRunLeaseService;
    }

    public void markIfTerminal(ChatEvent event) {
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
