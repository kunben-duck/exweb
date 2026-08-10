package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

/** Owner与fallback共用的stop终态原子提交入口。 */
final class ChatRunStopTerminalFinalizer {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunStopTerminalFinalizer.class);

    private final ChatRunStopAssistantProjector assistantProjector;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatRunApplicationService chatRunService;
    private final ChatStreamApplicationService chatStreamService;

    ChatRunStopTerminalFinalizer(ChatRunStopAssistantProjector assistantProjector,
                                 ChatRunTerminalCommitService terminalCommitService,
                                 ChatRunApplicationService chatRunService,
                                 ChatStreamApplicationService chatStreamService) {
        this.assistantProjector = assistantProjector;
        this.terminalCommitService = terminalCommitService;
        this.chatRunService = chatRunService;
        this.chatStreamService = chatStreamService;
    }

    Result commit(UserContext user,
                  ChatRun run,
                  String reason,
                  ChatSession sessionSnapshot,
                  AssistantAssembly assistant,
                  RunExecutionClaim ownerClaim) {
        ChatRunStopAssistantProjector.Projection projection = assistantProjector.project(
                user, run, reason, sessionSnapshot, assistant);
        ChatEvent cancelled = RunCancelledEvent.of(
                run.id(), run.sessionId(), run.cancelReason(),
                projection.messageReady(), projection.assistantMessageId());
        ChatRunTerminalCommitService.ExternalTerminalCommitCommand command = ownerClaim == null
                ? ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                        cancelled, run, projection.command(), projection.preserveExistingProjection())
                : ChatRunTerminalCommitService.ExternalTerminalCommitCommand.ownerStop(
                        cancelled, run, projection.command(), projection.preserveExistingProjection(), ownerClaim);
        ChatRunTerminalCommitService.ExternalTerminalCommitResult committed =
                terminalCommitService.commitExternalTerminal(command);
        ChatRun latest = committed.run() == null ? run : committed.run();
        chatRunService.synchronizeCommittedRunCache(latest);
        if (committed.committed()) {
            publishBestEffort(committed.event());
        }
        return new Result(latest, committed.event(), committed.committed(), projection);
    }

    private void publishBestEffort(ChatEvent event) {
        if (event == null) {
            return;
        }
        try {
            chatStreamService.publishPersisted(event);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "ChatRun stop terminal event was committed but realtime publication failed")
                    .runId(event.runId())
                    .operation("chat-run.stop.terminal-publish")
                    .attribute("eventType", event.type())
                    .build(), ex);
        }
    }

    record Result(
            ChatRun run,
            ChatEvent event,
            boolean committed,
            ChatRunStopAssistantProjector.Projection projection
    ) {
    }
}
