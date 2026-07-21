package com.huawei.it.ex.one.chat.application.mapper;

import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.intent.application.model.IntentAttachmentSnapshot;
import com.huawei.it.ex.one.intent.application.model.IntentCommandSnapshot;
import com.huawei.it.ex.one.intent.application.model.IntentMemoryRequest;
import com.huawei.it.ex.one.intent.application.model.IntentSessionSnapshot;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteSignalRequest;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;

/** One-to-one synchronous mapping at the Chat-to-Intent application boundary. */
public final class ChatIntentMapper {
    private ChatIntentMapper() {
    }

    public static RouteSignalRequest toRouteRequest(RouteRequestInput input) {
        return new RouteSignalRequest(input.runId(), input.user(), toSession(input.session()),
                toCommand(input.command()), toAttachments(input.attachments()), input.memory(), input.intentQuery());
    }

    public static IntentCommandSnapshot toCommand(ChatCommand command) {
        if (command == null) {
            return null;
        }
        return new IntentCommandSnapshot(command.commandId(), command.tenantId(), command.userId(),
                command.sessionId(), command.message(), command.metadata(), command.routeTrigger());
    }

    public static IntentSessionSnapshot toSession(ChatSession session) {
        return session == null ? null : new IntentSessionSnapshot(session.id());
    }

    public static List<IntentAttachmentSnapshot> toAttachments(List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
                .map(attachment -> new IntentAttachmentSnapshot(
                        attachment.documentId(), attachment.name(), attachment.contentType(), attachment.sizeBytes(),
                        attachment.tokenSize(), attachment.source()))
                .toList();
    }

    public static IntentMemoryRequest toMemoryRequest(ChatCommand command) {
        return command == null
                ? new IntentMemoryRequest(null, null, null, null)
                : new IntentMemoryRequest(command.tenantId(), command.userId(), command.sessionId(), command.message());
    }

    public record RouteRequestInput(
            String runId,
            UserContext user,
            ChatSession session,
            ChatCommand command,
            List<AttachmentRef> attachments,
            MemoryContext memory,
            String intentQuery
    ) {
    }
}
