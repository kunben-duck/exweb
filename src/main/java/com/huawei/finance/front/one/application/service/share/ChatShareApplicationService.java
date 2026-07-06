package com.huawei.finance.front.one.application.service.share;

import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.integration.share.ChatShareAccessPolicy;
import com.huawei.finance.front.one.application.integration.share.ChatShareRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageAttachment;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatShare;
import com.huawei.finance.front.one.domain.chat.ChatShareAttachmentSnapshot;
import com.huawei.finance.front.one.domain.chat.ChatShareMessageSnapshot;
import com.huawei.finance.front.one.domain.chat.ChatSharePage;
import com.huawei.finance.front.one.domain.chat.ChatShareSnapshot;
import com.huawei.finance.front.one.domain.chat.ChatShareSnapshotPart;
import com.huawei.finance.front.one.domain.chat.ChatShareUnavailableException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单轮问答分享应用服务。
 *
 * <p>该服务只处理分享生命周期和固定快照构造；分享可见性全部委托给
 * {@link ChatShareAccessPolicy}，避免把企业权限模型写死在编排层。</p>
 */
@Service
public class ChatShareApplicationService {
    private static final String STATUS_DELETED = "DELETED";
    private static final int MAX_TITLE_LENGTH = 120;

    private final ChatShareRepository shareRepository;
    private final ChatMessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final ChatShareAccessPolicy accessPolicy;

    public ChatShareApplicationService(ChatShareRepository shareRepository, ChatMessageRepository messageRepository,
                                       SessionRepository sessionRepository, IdGenerator idGenerator,
                                       PermissionChecker permissionChecker, ChatShareAccessPolicy accessPolicy) {
        this.shareRepository = shareRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public ChatShare create(UserContext user, CreateChatShareCommand command) {
        checkUser(user);
        CreateChatShareCommand safeCommand = command == null ? CreateChatShareCommand.empty() : command;
        Instant now = Instant.now();
        validateExpiresAt(safeCommand.expiresAt(), now);
        ChatMessage assistant = loadOwnedMessage(user, safeCommand.messageId());
        ensureAssistantMessage(assistant);
        ChatSession session = loadOwnedSession(user, assistant.sessionId());
        ensureSessionShareable(session);
        if (!accessPolicy.canCreate(user, assistant)) {
            throw new SecurityException("无权分享该消息");
        }
        ChatMessage question = loadOwnedMessage(user, assistant.parentMessageId());
        ensureParentQuestion(assistant, question);
        ChatShareSnapshot snapshot = buildSnapshot(user, question, assistant, now);
        String shareId = idGenerator.newId("share", IdGenerateContext.of(
                user.tenantId(), user.ownerUserId(), assistant.sessionId(), assistant.runId()));
        ChatShare share = new ChatShare(
                shareId,
                user.tenantId(),
                user.ownerUserId(),
                assistant.sessionId(),
                question.id(),
                assistant.id(),
                assistant.runId(),
                titleOrDefault(safeCommand.title(), question.content()),
                "SINGLE_TURN",
                "INTERNAL",
                "ACTIVE",
                safeCommand.expiresAt(),
                null,
                snapshot,
                now,
                now
        );
        return shareRepository.save(share);
    }

    public ChatShare get(UserContext user, String shareId) {
        checkUser(user);
        ChatShare share = loadShare(shareId);
        if (!accessPolicy.canView(user, share)) {
            throw new SecurityException("无权查看该分享");
        }
        ensureAccessibleLifecycle(share, Instant.now());
        return share;
    }

    @Transactional
    public ChatShare revoke(UserContext user, String shareId) {
        checkUser(user);
        ChatShare share = loadShare(shareId);
        if (!accessPolicy.canRevoke(user, share)) {
            throw new SecurityException("无权撤销该分享");
        }
        if (share.revoked()) {
            return share;
        }
        return shareRepository.save(share.revoke(Instant.now()));
    }

    public ChatSharePage listOwned(UserContext user, int curPage, int pageSize) {
        checkUser(user);
        return shareRepository.pageByOwner(user.tenantId(), user.ownerUserId(), curPage, pageSize);
    }

    private ChatShareSnapshot buildSnapshot(UserContext user, ChatMessage question, ChatMessage assistant, Instant now) {
        return new ChatShareSnapshot(
                toSnapshotMessage(user, question),
                toSnapshotMessage(user, assistant),
                assistant.parts().stream()
                        .filter(part -> Boolean.TRUE.equals(part.visible()))
                        .map(this::toSnapshotPart)
                        .toList(),
                now
        );
    }

    private ChatShareMessageSnapshot toSnapshotMessage(UserContext user, ChatMessage message) {
        List<ChatShareAttachmentSnapshot> attachments = messageRepository
                .findAttachments(user.tenantId(), user.ownerUserId(), message.id())
                .stream()
                .map(this::toAttachmentSnapshot)
                .toList();
        return new ChatShareMessageSnapshot(message.id(), message.sessionId(), message.role(), message.content(),
                message.runId(), message.metadataJson(), attachments, message.createdAt());
    }

    private ChatShareAttachmentSnapshot toAttachmentSnapshot(ChatMessageAttachment attachment) {
        return new ChatShareAttachmentSnapshot(
                attachment.documentId(),
                attachment.name(),
                attachment.contentType(),
                attachment.sizeBytes()
        );
    }

    private ChatShareSnapshotPart toSnapshotPart(ChatMessagePart part) {
        return new ChatShareSnapshotPart(part.id(), part.messageId(), part.runId(), part.partType(),
                part.sourceType(), part.contentText(), part.title(), part.status(), part.channel(),
                part.displayHint(), part.visible(), part.payload(), part.partOrder(), part.createdAt());
    }

    private void checkUser(UserContext user) {
        permissionChecker.checkChatPermission(user);
    }

    private ChatMessage loadOwnedMessage(UserContext user, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        return messageRepository.findByOwnerAndId(user.tenantId(), user.ownerUserId(), messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在或不属于当前用户: " + messageId));
    }

    private ChatSession loadOwnedSession(UserContext user, String sessionId) {
        return sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.ownerUserId(), sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId));
    }

    private ChatShare loadShare(String shareId) {
        if (shareId == null || shareId.isBlank()) {
            throw new IllegalArgumentException("shareId 不能为空");
        }
        return shareRepository.findById(shareId)
                .orElseThrow(() -> new IllegalArgumentException("分享不存在: " + shareId));
    }

    private void ensureAssistantMessage(ChatMessage message) {
        if (!"assistant".equals(message.role())) {
            throw new IllegalArgumentException("只能分享 assistant 消息");
        }
        if (message.parentMessageId() == null || message.parentMessageId().isBlank()) {
            throw new IllegalArgumentException("assistant 消息缺少父 user 节点，不能分享");
        }
    }

    private void ensureParentQuestion(ChatMessage assistant, ChatMessage question) {
        if (!assistant.sessionId().equals(question.sessionId()) || !"user".equals(question.role())) {
            throw new IllegalArgumentException("assistant 消息父节点不是同会话 user 消息，不能分享");
        }
    }

    private void ensureSessionShareable(ChatSession session) {
        if (STATUS_DELETED.equals(session.status())) {
            throw new IllegalArgumentException("已删除会话不能创建分享");
        }
    }

    private void validateExpiresAt(Instant expiresAt, Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt 必须晚于当前时间");
        }
    }

    private void ensureAccessibleLifecycle(ChatShare share, Instant now) {
        if (share.revoked()) {
            throw new ChatShareUnavailableException("SHARE_REVOKED", "分享已撤销");
        }
        if (share.expired(now)) {
            throw new ChatShareUnavailableException("SHARE_EXPIRED", "分享已过期");
        }
    }

    private String titleOrDefault(String title, String question) {
        String candidate = title == null || title.isBlank() ? question : title.trim();
        if (candidate == null || candidate.isBlank()) {
            return "问答分享";
        }
        String singleLine = candidate.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_TITLE_LENGTH ? singleLine : singleLine.substring(0, MAX_TITLE_LENGTH);
    }
}
