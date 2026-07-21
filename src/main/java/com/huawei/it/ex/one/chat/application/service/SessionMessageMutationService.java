package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.chat.application.repository.ChatMessageRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessageAttachment;
import com.huawei.it.ex.one.chat.domain.ChatMessagePart;
import com.huawei.it.ex.one.chat.domain.ChatMessagePartDraft;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.ChatRunMode;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SessionMessageMutationService {
    private final SessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final IdGenerator idGenerator;

    SessionMessageMutationService(SessionRepository sessionRepository,
                                  ChatMessageRepository messageRepository,
                                  IdGenerator idGenerator) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.idGenerator = idGenerator;
    }

    ChatRunMessagePlan prepareRunMessage(UserContext user, ChatCommand command, ChatSession session,
                                         String runId, List<AttachmentRef> attachments) {
        ChatRunMode mode = command.runMode() == null ? ChatRunMode.NEXT : command.runMode();
        return switch (mode) {
            case NEXT -> createNextUserMessage(user, command, session, runId, attachments);
            case EDIT_USER -> createEditedUserMessage(user, command, session, runId, attachments);
            case REGENERATE_ASSISTANT -> resolveRegeneratePlan(user, command, session);
            case CONTINUE_INTERACTION ->
                    throw new IllegalArgumentException("CONTINUE_INTERACTION 不创建普通 user 消息");
        };
    }

    ChatRunMessagePlan prepareIntentClarificationAnswer(
            IntentClarificationAnswerCommand command) {
        UserContext user = command.user();
        ChatSession session = command.session();
        String runId = command.runId();
        String parentAssistantMessageId = command.parentAssistantMessageId();
        String answerText = command.answerText();
        List<AttachmentRef> attachments = command.attachments();
        if (user == null || session == null) {
            throw new IllegalArgumentException("意图澄清回答缺少用户或会话上下文");
        }
        ChatMessage parent = requireMessageInSession(session, parentAssistantMessageId);
        if (!"assistant".equalsIgnoreCase(parent.role())) {
            throw new IllegalArgumentException("意图澄清回答的父节点必须是 assistant 消息");
        }
        ChatMessage answer = createUserMessage(new UserMessageCreateCommand(
                user.tenantId(), user.ownerUserId(), session, answerText, parent.id(), ChatRunMode.NEXT,
                runId, null, null, attachments == null ? List.of() : attachments));
        return new ChatRunMessagePlan(ChatRunMode.NEXT, parent.id(), answer, null);
    }

    ChatMessage saveUserMessage(ChatCommand command, ChatSession session) {
        return createUserMessage(new UserMessageCreateCommand(command.tenantId(), command.userId(), session,
                command.message(), null, command.runMode(), null, null, null, List.of()));
    }

    ChatMessage saveAssistantMessage(AssistantMessageSaveCommand command) {
        ChatSession session = command.session();
        String messageId = command.normalizedMessageId();
        if (messageId == null) {
            messageId = idGenerator.newId(
                    "msg", IdGenerateContext.of(command.tenantId(), command.userId(), session.id()));
        }
        ChatMessage parent = command.parentMessageId() == null
                ? null
                : requireMessageInSession(session, command.parentMessageId());
        Instant now = Instant.now();
        List<ChatMessagePart> parts = buildMessageParts(new MessagePartBuildContext(command.tenantId(),
                command.userId(), session.id(), messageId, command.runId(), command.content(),
                command.safePartDrafts(), now, command.appendAnswerPart()));
        ChatMessage message = new ChatMessage(
                messageId,
                command.tenantId(),
                command.userId(),
                session.id(),
                command.parentMessageId(),
                nextNodeOrder(session),
                parent == null ? 0 : parent.treeDepth() + 1,
                nextSiblingIndex(command.tenantId(), command.userId(), session.id(),
                        command.parentMessageId(), "assistant"),
                "assistant",
                command.content(),
                null,
                command.runId(),
                "NORMAL",
                false,
                null,
                null,
                null,
                command.regeneratedFromMessageId(),
                command.metadataJson(),
                parts,
                now
        );
        ChatMessage saved = messageRepository.save(message);
        sessionRepository.updateCurrentLeaf(command.tenantId(), command.userId(), session.id(), saved.id());
        return saved;
    }

    ChatMessage updateAssistantMessage(AssistantMessageUpdateCommand command) {
        ChatSession session = command.session();
        ChatMessage existing = requireMessageInSession(session, command.messageId());
        ensureUnlockedAssistantMessage(existing, "Interaction 续接 assistant 消息");
        Instant now = Instant.now();
        int startOrder = existing.parts() == null ? 1 : existing.parts().size() + 1;
        List<ChatMessagePart> parts = buildMessageParts(new MessagePartBuildContext(command.tenantId(),
                command.userId(), session.id(), existing.id(), command.runId(), command.content(),
                command.safePartDrafts(), now, startOrder, command.appendAnswerPart()));
        ChatMessage updated = new ChatMessage(
                existing.id(),
                command.tenantId(),
                command.userId(),
                session.id(),
                existing.parentMessageId(),
                existing.nodeOrder(),
                existing.treeDepth(),
                existing.siblingIndex(),
                "assistant",
                command.content(),
                existing.tokenCount(),
                command.runId(),
                existing.originType(),
                existing.locked(),
                existing.sourceSessionId(),
                existing.sourceMessageId(),
                existing.editedFromMessageId(),
                existing.regeneratedFromMessageId(),
                command.metadataJson(),
                parts,
                now
        );
        ChatMessage saved = messageRepository.updateAssistantMessage(updated);
        sessionRepository.updateCurrentLeaf(command.tenantId(), command.userId(), session.id(), saved.id());
        return saved;
    }

    ChatMessage requireMessageInSession(ChatSession session, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        return messageRepository.findByOwnerAndId(session.tenantId(), session.userId(), messageId)
                .filter(message -> session.id().equals(message.sessionId()))
                .orElseThrow(() -> new IllegalArgumentException("消息不存在或不属于当前会话: " + messageId));
    }

    private ChatRunMessagePlan createNextUserMessage(UserContext user, ChatCommand command, ChatSession session,
                                                     String runId, List<AttachmentRef> attachments) {
        String parentMessageId = blankToNull(command.parentMessageId()) == null
                ? session.currentLeafMessageId()
                : command.parentMessageId();
        ChatMessage message = createUserMessage(new UserMessageCreateCommand(
                user.tenantId(), user.ownerUserId(), session, command.message(), parentMessageId,
                ChatRunMode.NEXT, runId, null, null, attachments));
        return new ChatRunMessagePlan(ChatRunMode.NEXT, parentMessageId, message, null);
    }

    private ChatRunMessagePlan createEditedUserMessage(UserContext user, ChatCommand command, ChatSession session,
                                                       String runId, List<AttachmentRef> attachments) {
        ChatMessage edited = requireMessageInSession(session, command.editedMessageId());
        ensureUnlockedUserMessage(edited, "被编辑消息");
        ChatMessage message = createUserMessage(new UserMessageCreateCommand(
                user.tenantId(), user.ownerUserId(), session, command.message(), edited.parentMessageId(),
                ChatRunMode.EDIT_USER, runId, edited.id(), null, attachments));
        return new ChatRunMessagePlan(ChatRunMode.EDIT_USER, edited.parentMessageId(), message, null);
    }

    private ChatRunMessagePlan resolveRegeneratePlan(UserContext user, ChatCommand command, ChatSession session) {
        ChatMessage regenerated = requireMessageInSession(session, command.regeneratedMessageId());
        ensureUnlockedAssistantMessage(regenerated, "被重新生成消息");
        if (regenerated.parentMessageId() == null || regenerated.parentMessageId().isBlank()) {
            throw new IllegalArgumentException("assistant 消息缺少父 user 节点，不能重新生成");
        }
        ChatMessage userMessage = requireMessageInSession(session, regenerated.parentMessageId());
        if (!"user".equals(userMessage.role())) {
            throw new IllegalArgumentException("assistant 消息父节点不是 user 消息，不能重新生成");
        }
        sessionRepository.updateCurrentLeaf(user.tenantId(), user.ownerUserId(), session.id(), userMessage.id());
        return new ChatRunMessagePlan(
                ChatRunMode.REGENERATE_ASSISTANT, userMessage.id(), userMessage, regenerated.id());
    }

    private ChatMessage createUserMessage(UserMessageCreateCommand command) {
        List<AttachmentRef> attachments = command.safeAttachments();
        boolean attachmentOnlyNext = command.mode() == ChatRunMode.NEXT && !attachments.isEmpty();
        if ((command.content() == null || command.content().isBlank()) && !attachmentOnlyNext) {
            throw new IllegalArgumentException("用户消息不能为空");
        }
        String content = command.content() == null || command.content().isBlank() ? "" : command.content();
        ChatSession session = command.session();
        ChatMessage parent = command.parentMessageId() == null
                ? null
                : requireMessageInSession(session, command.parentMessageId());
        String messageId = idGenerator.newId(
                "msg", IdGenerateContext.of(command.tenantId(), command.userId(), session.id()));
        ChatMessage message = new ChatMessage(
                messageId,
                command.tenantId(),
                command.userId(),
                session.id(),
                command.parentMessageId(),
                nextNodeOrder(session),
                parent == null ? 0 : parent.treeDepth() + 1,
                nextSiblingIndex(command.tenantId(), command.userId(), session.id(),
                        command.parentMessageId(), "user"),
                "user",
                content,
                null,
                command.runId(),
                "NORMAL",
                false,
                null,
                null,
                command.mode() == ChatRunMode.EDIT_USER ? command.editedFromMessageId() : null,
                command.regeneratedFromMessageId(),
                null,
                Instant.now()
        );
        ChatMessage saved = messageRepository.save(message);
        saveAttachments(saved, attachments);
        sessionRepository.updateCurrentLeaf(command.tenantId(), command.userId(), session.id(), saved.id());
        return saved;
    }

    private List<ChatMessagePart> buildMessageParts(MessagePartBuildContext context) {
        List<ChatMessagePart> parts = new ArrayList<>();
        int order = context.startOrder();
        if (context.drafts() != null) {
            for (ChatMessagePartDraft draft : context.drafts()) {
                if (draft == null || draft.partType() == null || draft.partType().isBlank()) {
                    continue;
                }
                parts.add(new ChatMessagePart(
                        idGenerator.newId("part", IdGenerateContext.of(
                                context.tenantId(), context.userId(), context.sessionId())),
                        context.tenantId(),
                        context.userId(),
                        context.sessionId(),
                        context.messageId(),
                        context.runId(),
                        draft.partType(),
                        draft.sourceType(),
                        draft.contentText(),
                        draft.title(),
                        draft.status(),
                        draft.channel(),
                        draft.displayHint(),
                        draft.visible(),
                        draft.payload(),
                        order++,
                        context.now()
                ));
            }
        }
        if (context.appendAnswerPart()) {
            parts.add(new ChatMessagePart(
                    idGenerator.newId("part", IdGenerateContext.of(
                            context.tenantId(), context.userId(), context.sessionId())),
                    context.tenantId(),
                    context.userId(),
                    context.sessionId(),
                    context.messageId(),
                    context.runId(),
                    "ANSWER",
                    "message.snapshot",
                    context.content(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(
                            "content", context.content() == null ? "" : context.content(),
                            "serverTimestampMs", context.now().toEpochMilli()
                    ),
                    order,
                    context.now()
            ));
        }
        return List.copyOf(parts);
    }

    private long nextNodeOrder(ChatSession session) {
        return sessionRepository.nextNodeOrder(session.tenantId(), session.userId(), session.id());
    }

    private int nextSiblingIndex(String tenantId, String userId, String sessionId,
                                 String parentMessageId, String role) {
        return messageRepository.countSiblings(tenantId, userId, sessionId, parentMessageId, role) + 1;
    }

    private void ensureUnlockedUserMessage(ChatMessage message, String label) {
        if (!"user".equals(message.role())) {
            throw new IllegalArgumentException(label + "必须是 user 消息");
        }
        ensureUnlocked(message, label);
    }

    private void ensureUnlockedAssistantMessage(ChatMessage message, String label) {
        if (!"assistant".equals(message.role())) {
            throw new IllegalArgumentException(label + "必须是 assistant 消息");
        }
        ensureUnlocked(message, label);
    }

    private void ensureUnlocked(ChatMessage message, String label) {
        if (message.branchSnapshot()) {
            throw new IllegalStateException(label + "是分支历史快照，不能编辑或重新生成");
        }
    }

    private void saveAttachments(ChatMessage message, List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        int index = 0;
        for (AttachmentRef attachment : attachments) {
            if (attachment == null || attachment.documentId() == null || attachment.documentId().isBlank()) {
                continue;
            }
            messageRepository.saveAttachment(new ChatMessageAttachment(
                    idGenerator.newId("msg_att", IdGenerateContext.of(
                            message.tenantId(), message.userId(), message.sessionId())),
                    message.tenantId(),
                    message.userId(),
                    message.sessionId(),
                    message.id(),
                    attachment.documentId(),
                    ++index,
                    attachment.name(),
                    attachment.contentType(),
                    attachment.sizeBytes(),
                    null,
                    Instant.now()
            ));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
