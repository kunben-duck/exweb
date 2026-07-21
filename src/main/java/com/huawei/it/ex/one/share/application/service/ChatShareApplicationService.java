package com.huawei.it.ex.one.share.application.service;

import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourceAttachment;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourceMessage;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourcePart;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourceSession;
import com.huawei.it.ex.one.chat.application.service.ChatShareSourceService;
import com.huawei.it.ex.one.share.application.client.ChatShareAccessPolicy;
import com.huawei.it.ex.one.share.application.repository.ChatShareRepository;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.share.domain.ChatShare;
import com.huawei.it.ex.one.share.domain.ChatShareAttachmentSnapshot;
import com.huawei.it.ex.one.share.domain.ChatShareMessageSnapshot;
import com.huawei.it.ex.one.share.domain.ChatSharePage;
import com.huawei.it.ex.one.share.domain.ChatShareSnapshot;
import com.huawei.it.ex.one.share.domain.ChatShareSnapshotPart;
import com.huawei.it.ex.one.share.domain.ChatShareUnavailableException;
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
public class ChatShareApplicationService implements ChatShareService {
    private static final String STATUS_DELETED = "DELETED";
    private static final int MAX_TITLE_LENGTH = 120;

    private final ChatShareRepository shareRepository;
    private final ChatShareSourceService sourceService;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final ChatShareAccessPolicy accessPolicy;

    public ChatShareApplicationService(ChatShareRepository shareRepository,
                                       ChatShareSourceService sourceService, IdGenerator idGenerator,
                                       PermissionChecker permissionChecker, ChatShareAccessPolicy accessPolicy) {
        this.shareRepository = shareRepository;
        this.sourceService = sourceService;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    @Override
    public ChatShare create(UserContext user, CreateChatShareCommand command) {
        checkUser(user);
        CreateChatShareCommand safeCommand = command == null ? CreateChatShareCommand.empty() : command;
        Instant now = Instant.now();
        validateExpiresAt(safeCommand.expiresAt(), now);
        ChatShareSourceMessage assistant = loadOwnedMessage(user, safeCommand.messageId());
        ensureAssistantMessage(assistant);
        ChatShareSourceSession session = loadOwnedSession(user, assistant.sessionId());
        ensureSessionShareable(session);
        if (!accessPolicy.canCreate(user, assistant)) {
            throw new SecurityException("无权分享该消息");
        }
        ChatShareSourceMessage question = loadOwnedMessage(user, assistant.parentMessageId());
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

    @Override
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
    @Override
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

    @Override
    public ChatSharePage listOwned(UserContext user, int curPage, int pageSize) {
        checkUser(user);
        return shareRepository.pageByOwner(user.tenantId(), user.ownerUserId(), curPage, pageSize);
    }

    private ChatShareSnapshot buildSnapshot(
            UserContext user, ChatShareSourceMessage question,
            ChatShareSourceMessage assistant, Instant now) {
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

    private ChatShareMessageSnapshot toSnapshotMessage(UserContext user, ChatShareSourceMessage message) {
        List<ChatShareAttachmentSnapshot> attachments = sourceService
                .findAttachments(user, message.id())
                .stream()
                .map(this::toAttachmentSnapshot)
                .toList();
        return new ChatShareMessageSnapshot(message.id(), message.sessionId(), message.role(), message.content(),
                message.runId(), message.metadataJson(), attachments, message.createdAt());
    }

    private ChatShareAttachmentSnapshot toAttachmentSnapshot(ChatShareSourceAttachment attachment) {
        return new ChatShareAttachmentSnapshot(
                attachment.documentId(),
                attachment.name(),
                attachment.contentType(),
                attachment.sizeBytes()
        );
    }

    private ChatShareSnapshotPart toSnapshotPart(ChatShareSourcePart part) {
        return new ChatShareSnapshotPart(part.partId(), part.messageId(), part.runId(), part.partType(),
                part.sourceType(), part.contentText(), part.title(), part.status(), part.channel(),
                part.displayHint(), part.visible(), part.payload(), part.partOrder(), part.createdAt());
    }

    private void checkUser(UserContext user) {
        permissionChecker.checkChatPermission(user);
    }

    private ChatShareSourceMessage loadOwnedMessage(UserContext user, String messageId) {
        return sourceService.loadOwnedMessage(user, messageId);
    }

    private ChatShareSourceSession loadOwnedSession(UserContext user, String sessionId) {
        return sourceService.loadOwnedSession(user, sessionId);
    }

    private ChatShare loadShare(String shareId) {
        if (shareId == null || shareId.isBlank()) {
            throw new IllegalArgumentException("shareId 不能为空");
        }
        return shareRepository.findById(shareId)
                .orElseThrow(() -> new IllegalArgumentException("分享不存在: " + shareId));
    }

    private void ensureAssistantMessage(ChatShareSourceMessage message) {
        if (!"assistant".equals(message.role())) {
            throw new IllegalArgumentException("只能分享 assistant 消息");
        }
        if (message.parentMessageId() == null || message.parentMessageId().isBlank()) {
            throw new IllegalArgumentException("assistant 消息缺少父 user 节点，不能分享");
        }
    }

    private void ensureParentQuestion(
            ChatShareSourceMessage assistant, ChatShareSourceMessage question) {
        if (!assistant.sessionId().equals(question.sessionId()) || !"user".equals(question.role())) {
            throw new IllegalArgumentException("assistant 消息父节点不是同会话 user 消息，不能分享");
        }
    }

    private void ensureSessionShareable(ChatShareSourceSession session) {
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
