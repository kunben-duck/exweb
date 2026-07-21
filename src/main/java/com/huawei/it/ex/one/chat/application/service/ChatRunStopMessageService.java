package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.chat.application.model.AssistantAssembly;
import com.huawei.it.ex.one.chat.application.model.InteractionMessageStrategy;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.security.domain.UserContext;
import org.springframework.stereotype.Service;

@Service
public final class ChatRunStopMessageService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunStopCoordinator.class);
    private static final String INTERACTION_ID_METADATA = "interactionId";
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA = "interactionAssistantMessageId";
    private static final String USER_STOP_PARTIAL_ASSISTANT_METADATA =
            "{\"partial\":true,\"finishReason\":\"USER_STOP\",\"runStatus\":\"CANCELLED\"}";
    private static final String SESSION_DELETE_PARTIAL_ASSISTANT_METADATA =
            "{\"partial\":true,\"finishReason\":\"SESSION_DELETE\",\"runStatus\":\"CANCELLED\"}";

    private final SessionApplicationService sessionService;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunApplicationService chatRunService;
    private final IdGenerator idGenerator;

    public ChatRunStopMessageService(SessionApplicationService sessionService,
                                     ChatStreamApplicationService chatStreamService,
                                     ChatRunApplicationService chatRunService,
                                     IdGenerator idGenerator) {
        this.sessionService = sessionService;
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.idGenerator = idGenerator;
    }

    StopMessageTarget preparePartialAssistant(UserContext user, ChatRun run, String reason,
                                              ChatSession sessionSnapshot) {
        if (run.assistantMessageId() != null && !run.assistantMessageId().isBlank()) {
            return StopMessageTarget.ready(run.assistantMessageId());
        }
        boolean interactionContinuation = interactionContinuation(run);
        boolean newTurnInteraction = InteractionMessageStrategy.newTurn(run);
        String interactionAssistantMessageId = interactionAssistantMessageId(run);
        if (interactionContinuation && !newTurnInteraction && interactionAssistantMessageId == null) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Interaction continuation has no assistant ID; partial assistant persistence was skipped")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.partial-assistant.prepare")
                    .retryable(false)
                    .build());
            return StopMessageTarget.notReady();
        }
        String parentMessageId = firstNonBlank(run.userMessageId(), run.parentMessageId());
        if (parentMessageId == null) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "ChatRun has no parent user message; partial assistant persistence was skipped")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.partial-assistant.prepare")
                    .retryable(false)
                    .build());
            return StopMessageTarget.notReady();
        }
        try {
            AssistantAssembly assistant = new AssistantAssembly();
            chatStreamService.findPersistedRunEvents(user, run).forEach(assistant::observe);
            if (!assistant.shouldPersistMessage()) {
                return StopMessageTarget.notReady();
            }
            ChatSession session = sessionSnapshot == null
                    ? sessionService.getSession(user, run.sessionId())
                    : sessionSnapshot;
            String assistantMessageId = interactionContinuation && !newTurnInteraction
                    ? interactionAssistantMessageId
                    : idGenerator.newId("msg",
                            IdGenerateContext.of(user.tenantId(), user.ownerUserId(), session.id(), run.id()));
            AssistantMessageSaveCommand partialAssistant = new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.ownerUserId(),
                    session,
                    assistant.finalContent(),
                    run.id(),
                    parentMessageId,
                    null,
                    assistant.parts(),
                    partialMetadata(reason),
                    assistantMessageId
            );
            return StopMessageTarget.ready(assistantMessageId, partialAssistant);
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Partial assistant preparation failed during ChatRun stop")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.partial-assistant.prepare")
                    .attribute("stopReason", reason)
                    .build(), ex);
            return StopMessageTarget.notReady();
        }
    }

    StopMessageTarget persistPreparedPartialAssistant(ChatRun run, StopMessageTarget target) {
        if (target == null || target.partialAssistant() == null) {
            return target == null ? StopMessageTarget.notReady() : target;
        }
        try {
            ChatMessage savedAssistant = persistPartialAssistant(run, target.partialAssistant());
            chatRunService.bindAssistantMessage(run.id(), savedAssistant.id());
            return StopMessageTarget.ready(savedAssistant.id());
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                            "Legacy partial assistant persistence failed during ChatRun stop")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.stop.partial-assistant.persist")
                    .build(), ex);
            return StopMessageTarget.notReady();
        }
    }

    private ChatMessage persistPartialAssistant(ChatRun run, AssistantMessageSaveCommand command) {
        if (!interactionContinuation(run) || InteractionMessageStrategy.newTurn(run)) {
            return sessionService.saveAssistantMessage(command);
        }
        String assistantMessageId = interactionAssistantMessageId(run);
        if (assistantMessageId == null || !assistantMessageId.equals(command.normalizedMessageId())) {
            throw new IllegalStateException("Interaction stop partial assistant 必须复用原 assistantMessageId");
        }
        return sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                command.tenantId(),
                command.userId(),
                command.session(),
                assistantMessageId,
                command.content(),
                command.runId(),
                command.safePartDrafts(),
                command.metadataJson()
        ));
    }

    private boolean interactionContinuation(ChatRun run) {
        return metadataText(run, INTERACTION_ID_METADATA) != null;
    }

    private String interactionAssistantMessageId(ChatRun run) {
        return metadataText(run, INTERACTION_ASSISTANT_MESSAGE_ID_METADATA);
    }

    private String metadataText(ChatRun run, String key) {
        if (run == null || run.metadata() == null || key == null) {
            return null;
        }
        Object value = run.metadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String partialMetadata(String reason) {
        return "SESSION_DELETE".equals(reason)
                ? SESSION_DELETE_PARTIAL_ASSISTANT_METADATA
                : USER_STOP_PARTIAL_ASSISTANT_METADATA;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    record StopMessageTarget(boolean messageReady, String assistantMessageId,
                             AssistantMessageSaveCommand partialAssistant) {
        private static StopMessageTarget notReady() {
            return new StopMessageTarget(false, null, null);
        }

        private static StopMessageTarget ready(String assistantMessageId) {
            if (assistantMessageId == null || assistantMessageId.isBlank()) {
                return notReady();
            }
            return new StopMessageTarget(true, assistantMessageId, null);
        }

        private static StopMessageTarget ready(String assistantMessageId,
                                               AssistantMessageSaveCommand partialAssistant) {
            if (assistantMessageId == null || assistantMessageId.isBlank() || partialAssistant == null) {
                return notReady();
            }
            return new StopMessageTarget(true, assistantMessageId, partialAssistant);
        }
    }
}
