package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.model.ChatShareSourceAttachment;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourceMessage;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourcePart;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourceSession;
import com.huawei.it.ex.one.chat.application.repository.ChatMessageRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessageAttachment;
import com.huawei.it.ex.one.chat.domain.ChatMessagePart;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;
import org.springframework.stereotype.Service;

/** Repository-backed implementation of the share source boundary. */
@Service
public class DefaultChatShareSourceService implements ChatShareSourceService {
    private final ChatMessageRepository messageRepository;
    private final SessionRepository sessionRepository;

    public DefaultChatShareSourceService(
            ChatMessageRepository messageRepository, SessionRepository sessionRepository) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public ChatShareSourceMessage loadOwnedMessage(UserContext user, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        ChatMessage message = messageRepository
                .findByOwnerAndId(user.tenantId(), user.ownerUserId(), messageId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "消息不存在或不属于当前用户: " + messageId));
        return toSourceMessage(message);
    }

    @Override
    public ChatShareSourceSession loadOwnedSession(UserContext user, String sessionId) {
        return sessionRepository.findByTenantIdAndUserIdAndId(
                        user.tenantId(), user.ownerUserId(), sessionId)
                .map(session -> new ChatShareSourceSession(session.id(), session.status()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "会话不存在或不属于当前用户: " + sessionId));
    }

    @Override
    public List<ChatShareSourceAttachment> findAttachments(UserContext user, String messageId) {
        return messageRepository.findAttachments(user.tenantId(), user.ownerUserId(), messageId).stream()
                .map(this::toSourceAttachment)
                .toList();
    }

    private ChatShareSourceMessage toSourceMessage(ChatMessage message) {
        return new ChatShareSourceMessage(
                message.id(), message.tenantId(), message.userId(), message.sessionId(),
                message.parentMessageId(), message.role(), message.content(), message.runId(),
                message.metadataJson(), message.parts().stream().map(this::toSourcePart).toList(),
                message.createdAt());
    }

    private ChatShareSourcePart toSourcePart(ChatMessagePart part) {
        return new ChatShareSourcePart(
                part.id(), part.messageId(), part.runId(), part.partType(), part.sourceType(),
                part.contentText(), part.title(), part.status(), part.channel(), part.displayHint(),
                part.visible(), part.payload(), part.partOrder(), part.createdAt());
    }

    private ChatShareSourceAttachment toSourceAttachment(ChatMessageAttachment attachment) {
        return new ChatShareSourceAttachment(
                attachment.documentId(), attachment.name(), attachment.contentType(), attachment.sizeBytes());
    }
}
