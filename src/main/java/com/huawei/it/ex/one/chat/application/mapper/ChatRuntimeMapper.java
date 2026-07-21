package com.huawei.it.ex.one.chat.application.mapper;

import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunExecution;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeAttachmentSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeCommandSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeDocumentSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeExecutionSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeIntentSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeInteractionBindingRequest;
import com.huawei.it.ex.one.runtime.application.model.RuntimeLongTermMemorySnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeMemorySnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeMessageSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRouteMemorySnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRouteSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRouteType;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRunSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeTaskComplexity;
import java.util.List;

/** Pure one-to-one mappings at the Chat-to-Runtime application boundary. */
public final class ChatRuntimeMapper {
    private ChatRuntimeMapper() {
    }

    public static RuntimeCommandSnapshot command(ChatCommand source) {
        if (source == null) {
            return null;
        }
        return new RuntimeCommandSnapshot(source.sessionId(), source.message(), attachments(source.attachments()),
                source.metadata());
    }

    public static List<RuntimeAttachmentSnapshot> attachments(List<AttachmentRef> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(ChatRuntimeMapper::attachment).toList();
    }

    public static RuntimeAttachmentSnapshot attachment(AttachmentRef source) {
        return new RuntimeAttachmentSnapshot(source.documentId(), source.name(), source.contentType(),
                source.sizeBytes(), source.tokenSize(), source.source());
    }

    public static List<RuntimeDocumentSnapshot> documents(List<UploadedDocument> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(ChatRuntimeMapper::document).toList();
    }

    public static RuntimeDocumentSnapshot document(UploadedDocument source) {
        return new RuntimeDocumentSnapshot(source.id(), source.tenantId(), source.userId(), source.sessionId(),
                source.originalName(), source.bucket(), source.objectKey(), source.contentType(), source.sizeBytes(),
                source.status(), source.source(), source.tokenSize(), source.metadataJson(), source.createdAt(),
                source.updatedAt());
    }

    public static RuntimeIntentSnapshot intent(IntentDecision source) {
        if (source == null) {
            return null;
        }
        RuntimeTaskComplexity complexity = source.complexity() == null
                ? null
                : RuntimeTaskComplexity.valueOf(source.complexity().name());
        return new RuntimeIntentSnapshot(source.intentCode(), source.intentName(), complexity, source.confidence(),
                source.simpleTask(), source.candidateDomainAgentId(), source.slots(), source.missingSlots(),
                source.raw());
    }

    public static RuntimeRouteSnapshot route(RouteTarget source) {
        if (source == null) {
            return null;
        }
        RuntimeRouteType type = source.type() == null ? null : RuntimeRouteType.valueOf(source.type().name());
        return new RuntimeRouteSnapshot(type, source.selectedAgentCode(), source.routeSource(), source.score(),
                source.reason());
    }

    public static RuntimeMemorySnapshot memory(MemoryContext source) {
        if (source == null) {
            return null;
        }
        List<RuntimeMessageSnapshot> messages = source.recentMessages().stream()
                .map(message -> new RuntimeMessageSnapshot(
                        message.id(), message.role(), message.content(), message.createdAt()))
                .toList();
        List<RuntimeLongTermMemorySnapshot> longTerm = source.longTermMemories().stream()
                .map(memory -> new RuntimeLongTermMemorySnapshot(
                        memory.id(), memory.tenantId(), memory.userId(), memory.memoryType(), memory.content(),
                        memory.confidence(), memory.createdAt()))
                .toList();
        RuntimeRouteMemorySnapshot routeMemory = new RuntimeRouteMemorySnapshot(
                source.routeMemory().routeTrigger(), source.routeMemory().history(),
                source.routeMemory().lastIntentRejectReason());
        return new RuntimeMemorySnapshot(messages, longTerm, routeMemory);
    }

    public static RuntimeRunSnapshot run(ChatRun source) {
        if (source == null) {
            return null;
        }
        return new RuntimeRunSnapshot(source.id(), source.tenantId(), source.userId(), source.sessionId(),
                source.status() == null ? null : source.status().name(), source.routeType(), source.agentCode(),
                source.runtimeProvider(), source.runtimeSessionId(),
                source.runMode() == null ? null : source.runMode().name(), source.parentMessageId(),
                source.userMessageId(), source.assistantMessageId(), source.firstSeq(), source.lastSeq(),
                source.cancelReason(), source.startedAt(), source.finishedAt(), source.metadata(), source.createdAt(),
                source.updatedAt());
    }

    public static RuntimeExecutionSnapshot execution(ChatRunExecution source) {
        if (source == null) {
            return null;
        }
        return new RuntimeExecutionSnapshot(source.id(), source.runId(), source.tenantId(), source.userId(),
                source.sessionId(), source.executionStatus() == null ? null : source.executionStatus().name(),
                source.ownerInstanceId(), source.heartbeatAt(), source.leaseUntil(), source.fencingToken(),
                source.recoveryStrategy(), source.recoveredByInstanceId(), source.recoveryAttempts(),
                source.recoveryLeaseUntil(), source.runtimeResumeToken(), source.metadata(), source.createdAt(),
                source.updatedAt());
    }

    public static RuntimeInteractionBindingRequest interaction(ChatInteractionRequest source) {
        if (source == null) {
            return null;
        }
        return new RuntimeInteractionBindingRequest(source.id(), source.tenantId(), source.userId(),
                source.sessionId(), source.assistantMessageId(), source.runtimeBindingId(),
                source.runtimeSessionId());
    }
}
