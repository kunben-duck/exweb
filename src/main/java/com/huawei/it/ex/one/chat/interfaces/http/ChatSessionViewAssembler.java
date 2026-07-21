package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.application.service.ChatFeedbackService;
import com.huawei.it.ex.one.chat.application.service.ChatRunQueryService;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessageAttachment;
import com.huawei.it.ex.one.chat.domain.ChatMessageFeedback;
import com.huawei.it.ex.one.chat.domain.ChatMessagePage;
import com.huawei.it.ex.one.chat.domain.ChatMessagePart;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessageAttachmentDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessageDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessagePageDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessagePartDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessageTreeDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessageTreeNodeDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessageVersionInfoDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatSessionDto;
import com.huawei.it.ex.one.chat.interfaces.dto.MessageFeedbackDto;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Maps chat session and message facts to the existing frontend DTO contract. */
@Component
public class ChatSessionViewAssembler {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatSessionController.class);
    private static final String ASSISTANT_ROLE = "assistant";

    private final ChatFeedbackService feedbackService;
    private final ChatRunQueryService chatRunService;
    private final ChatMessageVersionViewAssembler versionViewAssembler;

    public ChatSessionViewAssembler(
            ChatFeedbackService feedbackService,
            ChatRunQueryService chatRunService,
            ChatMessageVersionViewAssembler versionViewAssembler) {
        this.feedbackService = feedbackService;
        this.chatRunService = chatRunService;
        this.versionViewAssembler = versionViewAssembler;
    }

    ChatSessionDto toDto(ChatSession session) {
        return toDto(session, null);
    }

    ChatSessionDto toDto(ChatSession session, String firstAssistantAnswer) {
        return new ChatSessionDto(
                session.id(),
                session.tenantId(),
                session.userId(),
                session.title(),
                session.status(),
                session.channel(),
                session.appId(),
                session.appName(),
                session.currentLeafMessageId(),
                session.rootSessionId(),
                session.branchSourceSessionId(),
                session.branchSourceMessageId(),
                session.hasUnread(),
                session.latestMessageSeq(),
                session.lastReadSeq(),
                firstAssistantAnswer,
                session.createdAt(),
                session.updatedAt());
    }

    ChatMessagePageDto toMessagePageDto(
            UserContext user,
            String sessionId,
            ChatMessagePage page,
            List<ChatMessage> sessionMessages) {
        Map<String, ChatMessageVersionInfoDto> versionInfos = page.items().isEmpty()
                ? Map.of()
                : versionViewAssembler.assemble(page.items(), sessionMessages);
        return new ChatMessagePageDto(
                toMessageDtos(user, sessionId, page.items(), versionInfos), page.nextCursor());
    }

    ChatMessageTreeDto toMessageTreeDto(
            UserContext user,
            ChatSession session,
            List<ChatMessage> messages) {
        List<ChatMessage> orderedMessages = messages == null ? List.of() : messages.stream()
                .sorted(Comparator.comparing(ChatMessage::nodeOrder).thenComparing(ChatMessage::createdAt))
                .toList();
        Set<String> messageIds = orderedMessages.stream().map(ChatMessage::id).collect(Collectors.toSet());
        Map<String, ChatMessageFeedback> feedbacks = feedbackService.findActiveByMessages(
                user, session.id(), orderedMessages);
        Map<String, ChatMessageVersionInfoDto> versionInfos =
                versionViewAssembler.assemble(orderedMessages, orderedMessages);
        Map<String, String> assistantSources = assistantSources(user, orderedMessages);
        Map<String, List<String>> childrenByParent = orderedMessages.stream()
                .filter(message -> message.parentMessageId() != null
                        && messageIds.contains(message.parentMessageId()))
                .collect(Collectors.groupingBy(
                        ChatMessage::parentMessageId,
                        LinkedHashMap::new,
                        Collectors.mapping(ChatMessage::id, Collectors.toList())));
        Map<String, ChatMessageTreeNodeDto> mapping = treeMapping(
                orderedMessages, feedbacks, versionInfos, assistantSources, childrenByParent);
        List<String> rootMessageIds = orderedMessages.stream()
                .filter(message -> message.parentMessageId() == null
                        || !messageIds.contains(message.parentMessageId()))
                .map(ChatMessage::id)
                .toList();
        return new ChatMessageTreeDto(
                session.id(), session.currentLeafMessageId(), rootMessageIds, mapping);
    }

    List<ChatMessageDto> toMessageDtos(
            UserContext user,
            String sessionId,
            List<ChatMessage> messages) {
        return toMessageDtos(user, sessionId, messages, Map.of());
    }

    MessageFeedbackDto toFeedbackDto(ChatMessageFeedback feedback) {
        return new MessageFeedbackDto(
                feedback.id(),
                feedback.messageId(),
                feedback.runId(),
                feedback.rating(),
                feedback.status(),
                feedback.reasonCode(),
                feedback.commentText(),
                feedback.createdAt(),
                feedback.updatedAt());
    }

    private Map<String, ChatMessageTreeNodeDto> treeMapping(
            List<ChatMessage> messages,
            Map<String, ChatMessageFeedback> feedbacks,
            Map<String, ChatMessageVersionInfoDto> versionInfos,
            Map<String, String> assistantSources,
            Map<String, List<String>> childrenByParent) {
        Map<String, ChatMessageTreeNodeDto> mapping = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            mapping.put(message.id(), new ChatMessageTreeNodeDto(
                    message.id(),
                    toMessageDto(
                            message,
                            feedbacks.get(message.id()),
                            assistantSources.get(message.runId()),
                            versionInfos.get(message.id())),
                    message.parentMessageId(),
                    childrenByParent.getOrDefault(message.id(), List.of())));
        }
        return mapping;
    }

    private List<ChatMessageDto> toMessageDtos(
            UserContext user,
            String sessionId,
            List<ChatMessage> messages,
            Map<String, ChatMessageVersionInfoDto> versionInfos) {
        Map<String, ChatMessageFeedback> feedbacks = feedbackService.findActiveByMessages(
                user, sessionId, messages);
        Map<String, String> assistantSources = assistantSources(user, messages);
        return messages.stream()
                .map(message -> toMessageDto(
                        message,
                        feedbacks.get(message.id()),
                        assistantSources.get(message.runId()),
                        versionInfos.get(message.id())))
                .toList();
    }

    private ChatMessageDto toMessageDto(
            ChatMessage message,
            ChatMessageFeedback feedback,
            String assistantSource,
            ChatMessageVersionInfoDto versionInfo) {
        String resolvedAssistantSource = ASSISTANT_ROLE.equals(message.role()) ? assistantSource : null;
        return new ChatMessageDto(
                message.id(),
                message.sessionId(),
                message.parentMessageId(),
                message.nodeOrder(),
                message.treeDepth(),
                message.siblingIndex(),
                message.role(),
                message.content(),
                message.tokenCount(),
                message.runId(),
                resolvedAssistantSource,
                message.originType(),
                message.locked(),
                message.sourceSessionId(),
                message.sourceMessageId(),
                message.editedFromMessageId(),
                message.regeneratedFromMessageId(),
                toPartDtos(message.parts()),
                toAttachmentDtos(message.attachments()),
                feedback == null ? null : toFeedbackDto(feedback),
                versionInfo,
                message.createdAt());
    }

    private List<ChatMessagePartDto> toPartDtos(List<ChatMessagePart> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        return parts.stream()
                .map(part -> new ChatMessagePartDto(
                        part.id(),
                        part.messageId(),
                        part.runId(),
                        part.partType(),
                        part.sourceType(),
                        part.contentText(),
                        part.title(),
                        part.status(),
                        part.channel(),
                        part.displayHint(),
                        part.visible(),
                        part.payload(),
                        part.partOrder(),
                        part.createdAt()))
                .toList();
    }

    private List<ChatMessageAttachmentDto> toAttachmentDtos(
            List<ChatMessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
                .map(attachment -> new ChatMessageAttachmentDto(
                        attachment.id(),
                        attachment.documentId(),
                        attachment.attachmentOrder(),
                        attachment.name(),
                        attachment.contentType(),
                        attachment.sizeBytes(),
                        attachment.sourceAttachmentId(),
                        attachment.createdAt()))
                .toList();
    }

    private Map<String, String> assistantSources(UserContext user, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Map.of();
        }
        List<String> runIds = messages.stream()
                .filter(message -> ASSISTANT_ROLE.equals(message.role()))
                .map(ChatMessage::runId)
                .filter(runId -> runId != null && !runId.isBlank())
                .distinct()
                .toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        try {
            return chatRunService.findOwnedRunsByIds(user, runIds).values().stream()
                    .filter(run -> run.runtimeProvider() != null && !run.runtimeProvider().isBlank())
                    .collect(Collectors.toMap(
                            ChatRun::id,
                            ChatRun::runtimeProvider,
                            (left, right) -> left,
                            LinkedHashMap::new));
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(
                            SystemErrorCode.DATABASE_READ_FAILED,
                            "Assistant source lookup failed; returning history without source metadata")
                    .operation("chat-history.assistant-source.read")
                    .attribute("runCount", runIds.size())
                    .build(), ex);
            return Map.of();
        }
    }
}
