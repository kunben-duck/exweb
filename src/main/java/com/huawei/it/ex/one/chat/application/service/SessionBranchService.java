package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.chat.application.repository.ChatMessageRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessageAttachment;
import com.huawei.it.ex.one.chat.domain.ChatMessagePart;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class SessionBranchService {
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final SessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final IdGenerator idGenerator;
    private final SessionMessageMutationService messageMutationService;

    SessionBranchService(SessionRepository sessionRepository,
                         ChatMessageRepository messageRepository,
                         IdGenerator idGenerator,
                         SessionMessageMutationService messageMutationService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.idGenerator = idGenerator;
        this.messageMutationService = messageMutationService;
    }

    String createBranch(UserContext user, ChatSession sourceSession,
                        String sourceMessageId, String title) {
        ChatMessage sourceLeaf = messageMutationService.requireMessageInSession(sourceSession, sourceMessageId);
        List<ChatMessage> sourcePath = messageRepository.findPathToMessage(
                user.tenantId(), user.ownerUserId(), sourceSession.id(), sourceLeaf.id());
        if (sourcePath.isEmpty()) {
            throw new IllegalArgumentException("分支来源消息不存在: " + sourceMessageId);
        }
        Instant now = Instant.now();
        String branchId = idGenerator.newId(
                "session", IdGenerateContext.of(user.tenantId(), user.ownerUserId()));
        ChatSession branch = sessionRepository.save(new ChatSession(
                branchId,
                user.tenantId(),
                user.ownerUserId(),
                title == null || title.isBlank() ? "分支 · " + sourceSession.title() : title.trim(),
                STATUS_ACTIVE,
                sourceSession.channel(),
                sourceSession.appId(),
                sourceSession.appName(),
                null,
                sourceSession.rootSessionId() == null ? sourceSession.id() : sourceSession.rootSessionId(),
                sourceSession.id(),
                sourceLeaf.id(),
                0L,
                null,
                now,
                now
        ));
        String previousNewMessageId = null;
        ChatMessage lastCopied = null;
        for (ChatMessage source : sourcePath) {
            String newMessageId = idGenerator.newId(
                    "msg", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), branch.id()));
            ChatMessage copy = new ChatMessage(
                    newMessageId,
                    user.tenantId(),
                    user.ownerUserId(),
                    branch.id(),
                    previousNewMessageId,
                    sessionRepository.nextNodeOrder(branch.tenantId(), branch.userId(), branch.id()),
                    lastCopied == null ? 0 : lastCopied.treeDepth() + 1,
                    1,
                    source.role(),
                    source.content(),
                    source.tokenCount(),
                    null,
                    "BRANCH_SNAPSHOT",
                    true,
                    source.sessionId(),
                    source.id(),
                    null,
                    null,
                    source.metadataJson(),
                    copyMessageParts(user, branch.id(), newMessageId, source.parts(), now),
                    now
            );
            lastCopied = messageRepository.save(copy);
            copyAttachments(user, source, lastCopied);
            previousNewMessageId = lastCopied.id();
        }
        sessionRepository.updateCurrentLeaf(
                user.tenantId(), user.ownerUserId(), branch.id(), previousNewMessageId);
        return branch.id();
    }

    private List<ChatMessagePart> copyMessageParts(UserContext user, String targetSessionId,
                                                   String targetMessageId,
                                                   List<ChatMessagePart> sourceParts, Instant now) {
        if (sourceParts == null || sourceParts.isEmpty()) {
            return List.of();
        }
        List<ChatMessagePart> copies = new ArrayList<>(sourceParts.size());
        int order = 1;
        for (ChatMessagePart sourcePart : sourceParts) {
            copies.add(new ChatMessagePart(
                    idGenerator.newId("part", IdGenerateContext.of(
                            user.tenantId(), user.ownerUserId(), targetSessionId)),
                    user.tenantId(),
                    user.ownerUserId(),
                    targetSessionId,
                    targetMessageId,
                    sourcePart.runId(),
                    sourcePart.partType(),
                    sourcePart.sourceType(),
                    sourcePart.contentText(),
                    sourcePart.title(),
                    sourcePart.status(),
                    sourcePart.channel(),
                    sourcePart.displayHint(),
                    sourcePart.visible(),
                    sourcePart.payload(),
                    order++,
                    now
            ));
        }
        return List.copyOf(copies);
    }

    private void copyAttachments(UserContext user, ChatMessage source, ChatMessage target) {
        int index = 0;
        for (ChatMessageAttachment attachment : messageRepository.findAttachments(
                user.tenantId(), user.ownerUserId(), source.id())) {
            messageRepository.saveAttachment(new ChatMessageAttachment(
                    idGenerator.newId("msg_att", IdGenerateContext.of(
                            user.tenantId(), user.ownerUserId(), target.sessionId())),
                    user.tenantId(),
                    user.ownerUserId(),
                    target.sessionId(),
                    target.id(),
                    attachment.documentId(),
                    ++index,
                    attachment.name(),
                    attachment.contentType(),
                    attachment.sizeBytes(),
                    attachment.id(),
                    Instant.now()
            ));
        }
    }
}
