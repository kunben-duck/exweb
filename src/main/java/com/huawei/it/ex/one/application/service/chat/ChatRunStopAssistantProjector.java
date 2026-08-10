package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import java.util.List;

/** 将当前run的AssistantAssembly投影为stop终态可原子保存的消息命令。 */
final class ChatRunStopAssistantProjector {
    private static final String INTERACTION_ID_METADATA = "interactionId";
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA = "interactionAssistantMessageId";
    private static final String USER_STOP_METADATA =
            "{\"partial\":true,\"finishReason\":\"USER_STOP\",\"runStatus\":\"CANCELLED\"}";
    private static final String SESSION_DELETE_METADATA =
            "{\"partial\":true,\"finishReason\":\"SESSION_DELETE\",\"runStatus\":\"CANCELLED\"}";

    private final SessionApplicationService sessionService;
    private final IdGenerator idGenerator;

    ChatRunStopAssistantProjector(SessionApplicationService sessionService, IdGenerator idGenerator) {
        this.sessionService = sessionService;
        this.idGenerator = idGenerator;
    }

    Projection project(UserContext user,
                       ChatRun run,
                       String reason,
                       ChatSession sessionSnapshot,
                       AssistantAssembly assistant) {
        if (run == null || assistant == null) {
            return Projection.notReady();
        }
        assistant.sealForStop();
        if (text(run.assistantMessageId()) != null) {
            return Projection.ready(run.assistantMessageId());
        }
        boolean continuation = text(metadata(run, INTERACTION_ID_METADATA)) != null;
        boolean reuseAssistant = continuation && !InteractionMessageStrategy.newTurn(run);
        String existingAssistantId = text(metadata(run, INTERACTION_ASSISTANT_MESSAGE_ID_METADATA));
        if (reuseAssistant && existingAssistantId == null) {
            return Projection.notReady();
        }

        boolean businessOutput = assistant.hasStopBusinessOutput();
        boolean controlOutput = assistant.hasStopControlOutput();
        boolean preserveExistingProjection = reuseAssistant && !businessOutput && controlOutput;
        if (reuseAssistant && !businessOutput && !controlOutput) {
            return Projection.notReady();
        }
        if (!reuseAssistant && !assistant.shouldPersistMessage()) {
            return Projection.notReady();
        }

        String parentMessageId = firstNonBlank(run.userMessageId(), run.parentMessageId());
        if (parentMessageId == null) {
            return Projection.notReady();
        }
        ChatSession session = sessionSnapshot == null
                ? sessionService.getSession(user, run.sessionId())
                : sessionSnapshot;
        String assistantMessageId = reuseAssistant
                ? existingAssistantId
                : idGenerator.newId("msg",
                        IdGenerateContext.of(user.tenantId(), user.ownerUserId(), session.id(), run.id()));
        List<ChatMessagePartDraft> parts = preserveExistingProjection
                ? AssistantAssembly.controlParts(assistant.parts())
                : assistant.parts();
        AssistantMessageSaveCommand command = new AssistantMessageSaveCommand(
                user.tenantId(),
                user.ownerUserId(),
                session,
                preserveExistingProjection ? "" : assistant.finalContent(),
                run.id(),
                parentMessageId,
                null,
                parts,
                preserveExistingProjection ? null : assistant.assistantMetadata(partialMetadata(reason)),
                assistantMessageId,
                !preserveExistingProjection && assistant.appendAnswerPart());
        return Projection.ready(assistantMessageId, command, preserveExistingProjection);
    }

    private String partialMetadata(String reason) {
        return "SESSION_DELETE".equals(reason) ? SESSION_DELETE_METADATA : USER_STOP_METADATA;
    }

    private Object metadata(ChatRun run, String key) {
        return run == null || run.metadata() == null ? null : run.metadata().get(key);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
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

    record Projection(
            boolean messageReady,
            String assistantMessageId,
            AssistantMessageSaveCommand command,
            boolean preserveExistingProjection
    ) {
        static Projection notReady() {
            return new Projection(false, null, null, false);
        }

        static Projection ready(String assistantMessageId) {
            return new Projection(true, assistantMessageId, null, false);
        }

        static Projection ready(String assistantMessageId,
                                AssistantMessageSaveCommand command,
                                boolean preserveExistingProjection) {
            return new Projection(true, assistantMessageId, command, preserveExistingProjection);
        }
    }
}
