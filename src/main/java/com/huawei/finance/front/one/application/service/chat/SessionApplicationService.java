package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.facade.ChatSessionFacade;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.finance.front.one.application.integration.share.ChatShareRepository;
import com.huawei.finance.front.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageAttachment;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import com.huawei.finance.front.one.domain.chat.ChatMessagePartDraft;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunMessagePlan;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionNumberPage;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.scheduler.Schedulers;

/**
 * 会话与消息应用服务。
 *
 * <p>负责会话创建、用户消息落库和助手最终回复落库。</p>
 */
@Service
public class SessionApplicationService implements ChatSessionFacade {
    private static final Logger log = LoggerFactory.getLogger(SessionApplicationService.class);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String STATUS_DELETED = "DELETED";
    private static final int MAX_BATCH_DELETE_SIZE = 100;

    private final SessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final ChatRunApplicationService chatRunService;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final ChatShareRepository shareRepository;
    private final ObjectProvider<ChatRunStopCoordinator> stopCoordinatorProvider;
    private final ObjectProvider<ChatInteractionApplicationService> interactionServiceProvider;

    @Autowired
    public SessionApplicationService(SessionRepository sessionRepository, ChatMessageRepository messageRepository, IdGenerator idGenerator,
                                     PermissionChecker permissionChecker, ChatRunApplicationService chatRunService,
                                     RuntimeBindingApplicationService runtimeBindingService,
                                     ChatShareRepository shareRepository,
                                     ObjectProvider<ChatRunStopCoordinator> stopCoordinatorProvider,
                                     ObjectProvider<ChatInteractionApplicationService> interactionServiceProvider) {
        this.sessionRepository = sessionRepository; this.messageRepository = messageRepository; this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.chatRunService = chatRunService;
        this.runtimeBindingService = runtimeBindingService;
        this.shareRepository = shareRepository;
        this.stopCoordinatorProvider = stopCoordinatorProvider;
        this.interactionServiceProvider = interactionServiceProvider;
    }

    SessionApplicationService(SessionRepository sessionRepository, ChatMessageRepository messageRepository, IdGenerator idGenerator,
                              PermissionChecker permissionChecker, ChatRunApplicationService chatRunService,
                              RuntimeBindingApplicationService runtimeBindingService,
                              ChatShareRepository shareRepository,
                              ObjectProvider<ChatRunStopCoordinator> stopCoordinatorProvider) {
        this(sessionRepository, messageRepository, idGenerator, permissionChecker, chatRunService,
                runtimeBindingService, shareRepository, stopCoordinatorProvider, null);
    }

    SessionApplicationService(SessionRepository sessionRepository, ChatMessageRepository messageRepository, IdGenerator idGenerator,
                              PermissionChecker permissionChecker, ChatRunApplicationService chatRunService,
                              RuntimeBindingApplicationService runtimeBindingService,
                              ChatShareRepository shareRepository) {
        this(sessionRepository, messageRepository, idGenerator, permissionChecker, chatRunService,
                runtimeBindingService, shareRepository, null, null);
    }

    SessionApplicationService(SessionRepository sessionRepository, ChatMessageRepository messageRepository, IdGenerator idGenerator,
                              PermissionChecker permissionChecker) {
        this(sessionRepository, messageRepository, idGenerator, permissionChecker, null, null, null);
    }

    public ChatSession loadOrCreate(ChatCommand command) {
        // 聊天主编排会先把 UserContext 回填到 ChatCommand；这里只根据已识别身份维护会话归属。
        if (command.sessionId() == null || command.sessionId().isBlank()) {
            return createOwnedSession(command.tenantId(), command.userId(), shortTitle(command.message()),
                    command.channel(), new SessionAppTag(command.appId(), command.appName()));
        }
        ChatSession session = requireOwnedSession(command.tenantId(), command.userId(), command.sessionId());
        validateAppTag(session, command.appId(), command.appName());
        return touch(session);
    }

    @Override
    public ChatSession createSession(UserContext user, String title, String channel) {
        return createSession(user, title, channel, null, null);
    }

    @Override
    public ChatSession createSession(UserContext user, String title, String channel, String appId, String appName) {
        checkChatUser(user);
        return createOwnedSession(user.tenantId(), user.ownerUserId(), title, channel,
                new SessionAppTag(appId, appName));
    }

    @Override
    public ChatSession getSession(UserContext user, String sessionId) {
        checkChatUser(user);
        return requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId);
    }

    @Override
    public ChatSession markSessionRead(UserContext user, String sessionId, long readThroughSeq) {
        checkChatUser(user);
        if (readThroughSeq < 0L) {
            throw new IllegalArgumentException("readThroughSeq 不能小于 0");
        }
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        return sessionRepository.markReadThrough(
                session.tenantId(), session.userId(), session.id(), readThroughSeq);
    }

    @Override
    public ChatMessagePage listMessages(UserContext user, String sessionId, String cursor, int limit) {
        return listMessages(user, sessionId, null, cursor, limit);
    }

    @Override
    public ChatMessagePage listMessages(UserContext user, String sessionId, String leafMessageId, String cursor, int limit) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        if (leafMessageId != null && !leafMessageId.isBlank()) {
            requireMessageInSession(session, leafMessageId);
        }
        return messageRepository.pageMessages(new ChatMessagePageQuery(session.tenantId(), session.userId(),
                session.id(), leafMessageId, cursor, limit));
    }

    @Override
    public List<ChatMessage> listMessageTree(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        return messageRepository.findAllBySession(session.tenantId(), session.userId(), session.id());
    }

    @Override
    public List<ChatMessage> listMessageTreeNodes(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        return messageRepository.findAllMessageNodesBySession(session.tenantId(), session.userId(), session.id());
    }

    @Override
    public ChatSessionPage listSessions(UserContext user, String cursor, int limit) {
        return listSessions(user, null, cursor, limit);
    }

    @Override
    public ChatSessionPage listSessions(UserContext user, String appId, String cursor, int limit) {
        checkChatUser(user);
        return sessionRepository.pageByTenantIdAndUserId(
                user.tenantId(), user.ownerUserId(), normalizeTag(appId), cursor, limit);
    }

    @Override
    public ChatSessionNumberPage listSessionsByPage(UserContext user, int curPage, int pageSize) {
        return listSessionsByPage(user, null, curPage, pageSize);
    }

    @Override
    public ChatSessionNumberPage listSessionsByPage(UserContext user, String appId, int curPage, int pageSize) {
        checkChatUser(user);
        return sessionRepository.pageNumberByTenantIdAndUserId(
                user.tenantId(), user.ownerUserId(), normalizeTag(appId), curPage, pageSize);
    }

    @Override
    public Map<String, String> findFirstAssistantAnswers(UserContext user, List<ChatSession> sessions) {
        checkChatUser(user);
        if (sessions == null || sessions.isEmpty()) {
            return Map.of();
        }
        List<String> sessionIds = sessions.stream()
                .filter(session -> session != null)
                .filter(session -> user.tenantId().equals(session.tenantId()))
                .filter(session -> user.ownerUserId().equals(session.userId()))
                .map(ChatSession::id)
                .distinct()
                .toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return messageRepository.findFirstAssistantMessagesBySessionIds(user.tenantId(), user.ownerUserId(), sessionIds)
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().content() == null ? "" : entry.getValue().content()));
    }

    @Override
    public ChatSession renameSession(UserContext user, String sessionId, String title) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        String safeTitle = title == null || title.isBlank() ? session.title() : title.trim();
        return saveWith(session, safeTitle, session.status());
    }

    @Override
    public ChatSession archiveSession(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        return saveWith(session, session.title(), STATUS_ARCHIVED);
    }

    @Override
    public ChatSession restoreSession(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        return saveWith(session, session.title(), STATUS_ACTIVE);
    }

    @Override
    public ChatSession deleteSession(UserContext user, String sessionId) {
        return deleteSessions(user, List.of(sessionId)).getFirst();
    }

    @Override
    @Transactional
    public List<ChatSession> deleteSessions(UserContext user, List<String> sessionIds) {
        checkChatUser(user);
        List<String> normalizedIds = normalizeDeleteSessionIds(sessionIds);
        List<ChatSession> sessions = normalizedIds.stream()
                .map(sessionId -> requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false))
                .toList();
        List<SessionDeleteRunPlan> activeRunPlans = activeRunPlansForDelete(user, sessions);
        List<ChatSession> deleted = new ArrayList<>(sessions.size());
        for (ChatSession session : sessions) {
            ChatSession deletedSession = saveWith(session, session.title(), STATUS_DELETED);
            if (runtimeBindingService != null) {
                runtimeBindingService.cancelAllForSession(user.tenantId(), user.ownerUserId(), session.id());
            }
            if (shareRepository != null) {
                shareRepository.revokeActiveBySession(user.tenantId(), user.ownerUserId(), session.id(), Instant.now());
            }
            ChatInteractionApplicationService interactionService = interactionServiceProvider == null ? null : interactionServiceProvider.getIfAvailable();
            if (interactionService != null) {
                interactionService.cancelOpenBySession(user, session.id());
            }
            deleted.add(deletedSession);
        }
        stopActiveRunsAfterDeleteCommit(user, activeRunPlans);
        return List.copyOf(deleted);
    }

    private List<SessionDeleteRunPlan> activeRunPlansForDelete(UserContext user, List<ChatSession> sessions) {
        if (sessions == null || sessions.isEmpty() || stopCoordinatorProvider == null) {
            return List.of();
        }
        if (stopCoordinatorProvider.getIfAvailable() == null || chatRunService == null) {
            return List.of();
        }
        List<SessionDeleteRunPlan> plans = new ArrayList<>();
        for (ChatSession session : sessions) {
            chatRunService.findActiveRun(user, session.id())
                    .ifPresent(run -> plans.add(new SessionDeleteRunPlan(session, run)));
        }
        return List.copyOf(plans);
    }

    private void stopActiveRunsAfterDeleteCommit(UserContext user, List<SessionDeleteRunPlan> plans) {
        if (plans == null || plans.isEmpty() || stopCoordinatorProvider == null) {
            return;
        }
        ChatRunStopCoordinator stopCoordinator = stopCoordinatorProvider.getIfAvailable();
        if (stopCoordinator == null) {
            return;
        }
        Runnable stopTask = () -> plans.forEach(plan -> {
            try {
                stopCoordinator.stopRunForSessionDelete(user, plan.run(), plan.session());
            } catch (Exception ex) {
                log.warn("Failed to stop active run after session delete. sessionId={}, runId={}, error={}",
                        plan.session().id(), plan.run().id(), ex.getMessage(), ex);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            /*
             * run.cancelled 会立即发布到 WebSocket/Redis。放到删除事务提交后执行，
             * 避免前端收到一个尚未提交、暂时无法通过 Event Resume 恢复的终态事件。
             * 这里再切到独立工作线程，避免 afterCommit 回调复用尚未解绑的事务资源去写 run/event/message。
             */
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Schedulers.boundedElastic().schedule(stopTask);
                }
            });
        } else {
            stopTask.run();
        }
    }

    private record SessionDeleteRunPlan(ChatSession session, ChatRun run) {}

    /**
     * 根据 runMode 创建或定位本轮用户消息。
     *
     * <p>编辑历史消息不会覆盖旧消息，而是在旧消息父节点下创建新的 user sibling。
     * 重新生成回答不会创建新的 user 消息，而是复用被重新生成回答的父 user 消息。</p>
     */
    public ChatRunMessagePlan prepareRunMessage(UserContext user, ChatCommand command, ChatSession session,
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

    /**
     * 将已受理的意图澄清答案保存为普通 user 消息节点。
     *
     * <p>调用方必须把本方法与 continuation run 插入、旧 Interaction ANSWERED 更新放在同一事务中。</p>
     */
    ChatRunMessagePlan prepareIntentClarificationAnswer(UserContext user, ChatSession session, String runId,
                                                        String parentAssistantMessageId, String answerText) {
        if (user == null || session == null) {
            throw new IllegalArgumentException("意图澄清回答缺少用户或会话上下文");
        }
        ChatMessage parent = requireMessageInSession(session, parentAssistantMessageId);
        if (!"assistant".equalsIgnoreCase(parent.role())) {
            throw new IllegalArgumentException("意图澄清回答的父节点必须是 assistant 消息");
        }
        ChatMessage answer = createUserMessage(new UserMessageCreateCommand(
                user.tenantId(), user.ownerUserId(), session, answerText, parent.id(), ChatRunMode.NEXT,
                runId, null, null, List.of()));
        return new ChatRunMessagePlan(ChatRunMode.NEXT, parent.id(), answer, null);
    }

    /**
     * 在调用方已有事务内锁定会话消息树，统一 admission 与终态提交的锁顺序。
     */
    void lockForMessageMutation(String tenantId, String userId, ChatSession session) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank() || session == null) {
            throw new IllegalArgumentException("会话消息写入锁参数不完整");
        }
        if (!tenantId.equals(session.tenantId()) || !userId.equals(session.userId())) {
            throw new SecurityException("会话不属于当前用户");
        }
        sessionRepository.lockForMessageMutation(tenantId, userId, session.id());
    }

    /** 在当前终态事务内推进最新可见 assistant 消息水位。 */
    void advanceLatestMessageSeq(UserContext user, ChatSession session, long messageSeq) {
        if (user == null || session == null || messageSeq < 0L) {
            throw new IllegalArgumentException("会话消息水位参数不合法");
        }
        sessionRepository.advanceLatestMessageSeq(
                user.tenantId(), user.ownerUserId(), session.id(), messageSeq);
    }

    public ChatMessage saveUserMessage(ChatCommand command, ChatSession session) {
        return createUserMessage(new UserMessageCreateCommand(command.tenantId(), command.userId(), session,
                command.message(), null, command.runMode(), null, null, null, List.of()));
    }

    public ChatMessage saveAssistantMessage(String tenantId, String userId, String sessionId, String content) {
        ChatSession session = requireOwnedSession(tenantId, userId, sessionId, false);
        return saveAssistantMessage(new AssistantMessageSaveCommand(tenantId, userId, session, content,
                null, session.currentLeafMessageId(), null, List.of(), null));
    }

    /**
     * 保存 assistant 回复及扩展元数据。
     *
     * <p>parts 在 run.completed 后写入；用户主动 stop 且已经产生 assistant 正文或用户可见过程
     * parts 时，也会把截止 stop 时已落库的内容固化为历史消息。run.failed 和 watchdog 故障仍不保存
     * 半截 assistant。</p>
     */
    public ChatMessage saveAssistantMessage(AssistantMessageSaveCommand command) {
        // 助手消息在事件流结束后保存完整文本，避免保存大量碎片 delta。
        ChatSession session = command.session();
        String messageId = command.normalizedMessageId();
        if (messageId == null) {
            messageId = idGenerator.newId("msg", IdGenerateContext.of(command.tenantId(), command.userId(), session.id()));
        }
        ChatMessage parent = command.parentMessageId() == null ? null : requireMessageInSession(session, command.parentMessageId());
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
                nextSiblingIndex(command.tenantId(), command.userId(), session.id(), command.parentMessageId(), "assistant"),
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

    /**
     * 更新已有 assistant 消息，并追加本次续接产生的 parts。
     *
     * <p>Interaction 续接不会创建新的普通 user/assistant 节点；澄清请求、用户回答和最终回答都挂在
     * 同一条 assistant 消息下，避免历史消息路径被一次澄清拆成多轮问答。</p>
     */
    public ChatMessage updateAssistantMessage(AssistantMessageUpdateCommand command) {
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

    /**
     * 查询某条消息的同父同角色候选版本。
     */
    public List<ChatMessage> listVariants(UserContext user, String sessionId, String messageId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        ChatMessage message = requireMessageInSession(session, messageId);
        return messageRepository.findSiblings(user.tenantId(), user.ownerUserId(), session.id(),
                message.parentMessageId(), message.role());
    }

    /**
     * 切换会话当前 active path 到指定消息。
     *
     * <p>前端的消息版本游标通常挂在 user 气泡下。为了让用户切换问题版本时仍然看到
     * 该问题下已有的回答，这里会在目标 user 消息存在 assistant 子节点时自动选择最新
     * assistant 子节点作为真正 leaf；没有回答时才把 user 消息本身作为 leaf。</p>
     */
    public ChatSession selectPath(UserContext user, String sessionId, String leafMessageId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        ChatMessage selected = requireMessageInSession(session, leafMessageId);
        String effectiveLeafMessageId = effectiveLeafForPathSelection(user, session, selected);
        sessionRepository.updateCurrentLeaf(user.tenantId(), user.ownerUserId(), session.id(), effectiveLeafMessageId);
        return requireOwnedSession(user.tenantId(), user.ownerUserId(), session.id(), false);
    }

    /**
     * 从指定消息创建只读历史快照分支。
     */
    public ChatSession createBranch(UserContext user, String sessionId, String sourceMessageId, String title) {
        checkChatUser(user);
        ChatSession sourceSession = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        ChatMessage sourceLeaf = requireMessageInSession(sourceSession, sourceMessageId);
        List<ChatMessage> sourcePath = messageRepository.findPathToMessage(user.tenantId(), user.ownerUserId(),
                sourceSession.id(), sourceLeaf.id());
        if (sourcePath.isEmpty()) {
            throw new IllegalArgumentException("分支来源消息不存在: " + sourceMessageId);
        }
        Instant now = Instant.now();
        String branchId = idGenerator.newId("session", IdGenerateContext.of(user.tenantId(), user.ownerUserId()));
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
            String newMessageId = idGenerator.newId("msg", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), branch.id()));
            ChatMessage copy = new ChatMessage(
                    newMessageId,
                    user.tenantId(),
                    user.ownerUserId(),
                    branch.id(),
                    previousNewMessageId,
                    nextNodeOrder(branch),
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
        sessionRepository.updateCurrentLeaf(user.tenantId(), user.ownerUserId(), branch.id(), previousNewMessageId);
        return requireOwnedSession(user.tenantId(), user.ownerUserId(), branch.id(), false);
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
                        idGenerator.newId("part", IdGenerateContext.of(context.tenantId(), context.userId(), context.sessionId())),
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
                    idGenerator.newId("part", IdGenerateContext.of(context.tenantId(), context.userId(), context.sessionId())),
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
                    Map.of("content", context.content() == null ? "" : context.content()),
                    order,
                    context.now()
            ));
        }
        return List.copyOf(parts);
    }

    private List<ChatMessagePart> copyMessageParts(UserContext user, String targetSessionId, String targetMessageId,
                                                   List<ChatMessagePart> sourceParts, Instant now) {
        if (sourceParts == null || sourceParts.isEmpty()) {
            return List.of();
        }
        List<ChatMessagePart> copies = new ArrayList<>(sourceParts.size());
        int order = 1;
        for (ChatMessagePart sourcePart : sourceParts) {
            copies.add(new ChatMessagePart(
                    idGenerator.newId("part", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), targetSessionId)),
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

    private String shortTitle(String text) { return text == null ? "新会话" : text.substring(0, Math.min(40, text.length())); }

    private void checkChatUser(UserContext user) {
        permissionChecker.checkChatPermission(user);
    }

    private ChatSession createOwnedSession(String tenantId, String userId, String title, String channel,
                                           SessionAppTag appTag) {
        Instant now = Instant.now();
        String sessionId = idGenerator.newId("session", IdGenerateContext.of(tenantId, userId));
        String safeTitle = title == null || title.isBlank() ? "新会话" : title;
        String safeChannel = channel == null || channel.isBlank() ? "web" : channel;
        return sessionRepository.save(new ChatSession(sessionId, tenantId, userId, safeTitle, STATUS_ACTIVE, safeChannel,
                appTag.appId(), appTag.appName(), null, sessionId, null, null, 0L, null, now, now));
    }

    private record SessionAppTag(String appId, String appName) {
    }

    private ChatSession requireOwnedSession(String tenantId, String userId, String sessionId) {
        return requireOwnedSession(tenantId, userId, sessionId, true);
    }

    private ChatSession requireOwnedSession(String tenantId, String userId, String sessionId, boolean activeRequired) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return sessionRepository.findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .map(session -> activeRequired ? ensureActive(session) : ensureNotDeleted(session))
                .orElseThrow(() -> sessionRepository.findById(sessionId).isPresent()
                        ? new SecurityException("会话不属于当前用户")
                        : new IllegalArgumentException("会话不存在: " + sessionId));
    }

    private ChatSession ensureActive(ChatSession session) {
        ensureNotDeleted(session);
        if (!STATUS_ACTIVE.equals(session.status())) {
            throw new IllegalStateException("会话不可用: " + session.id());
        }
        return session;
    }

    private ChatSession ensureNotDeleted(ChatSession session) {
        if (STATUS_DELETED.equals(session.status())) {
            throw new IllegalArgumentException("会话不存在: " + session.id());
        }
        return session;
    }

    private ChatSession touch(ChatSession session) {
        ChatSession touched = new ChatSession(session.id(), session.tenantId(), session.userId(), session.title(),
                session.status(), session.channel(), session.appId(), session.appName(),
                session.currentLeafMessageId(), session.rootSessionId(),
                session.branchSourceSessionId(), session.branchSourceMessageId(), session.lastNodeOrder(),
                session.latestMessageSeq(), session.lastReadSeq(), session.metadataJson(),
                session.createdAt(), Instant.now());
        return sessionRepository.save(touched);
    }

    private ChatSession saveWith(ChatSession session, String title, String status) {
        ChatSession updated = new ChatSession(session.id(), session.tenantId(), session.userId(), title, status,
                session.channel(), session.appId(), session.appName(), session.currentLeafMessageId(), session.rootSessionId(),
                session.branchSourceSessionId(), session.branchSourceMessageId(), session.lastNodeOrder(),
                session.latestMessageSeq(), session.lastReadSeq(), session.metadataJson(),
                session.createdAt(), Instant.now());
        return sessionRepository.save(updated);
    }

    /** 校验请求显式携带的 App Tag 与已有会话一致；未携带字段不参与比较。 */
    public void validateAppTag(UserContext user, String sessionId, String appId, String appName) {
        checkChatUser(user);
        validateAppTag(requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId), appId, appName);
    }

    private void validateAppTag(ChatSession session, String appId, String appName) {
        String normalizedAppId = normalizeTag(appId);
        String normalizedAppName = normalizeTag(appName);
        if (normalizedAppId == null && normalizedAppName != null) {
            throw new IllegalArgumentException("appName 不能脱离 appId 单独使用");
        }
        if (normalizedAppId != null && !normalizedAppId.equals(session.appId())) {
            throw new IllegalArgumentException("appId 与已有会话不一致");
        }
        if (normalizedAppName != null && !normalizedAppName.equals(session.appName())) {
            throw new IllegalArgumentException("appName 与已有会话不一致");
        }
    }

    private String normalizeTag(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ChatRunMessagePlan createNextUserMessage(UserContext user, ChatCommand command, ChatSession session,
                                                     String runId, List<AttachmentRef> attachments) {
        String parentMessageId = blankToNull(command.parentMessageId()) == null
                ? session.currentLeafMessageId()
                : command.parentMessageId();
        ChatMessage message = createUserMessage(new UserMessageCreateCommand(user.tenantId(), user.ownerUserId(), session,
                command.message(), parentMessageId, ChatRunMode.NEXT, runId, null, null, attachments));
        return new ChatRunMessagePlan(ChatRunMode.NEXT, parentMessageId, message, null);
    }

    private ChatRunMessagePlan createEditedUserMessage(UserContext user, ChatCommand command, ChatSession session,
                                                       String runId, List<AttachmentRef> attachments) {
        ChatMessage edited = requireMessageInSession(session, command.editedMessageId());
        ensureUnlockedUserMessage(edited, "被编辑消息");
        ChatMessage message = createUserMessage(new UserMessageCreateCommand(user.tenantId(), user.ownerUserId(), session,
                command.message(), edited.parentMessageId(), ChatRunMode.EDIT_USER, runId, edited.id(), null,
                attachments));
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
        return new ChatRunMessagePlan(ChatRunMode.REGENERATE_ASSISTANT, userMessage.id(), userMessage, regenerated.id());
    }

    private ChatMessage createUserMessage(UserMessageCreateCommand command) {
        if (command.content() == null || command.content().isBlank()) {
            throw new IllegalArgumentException("用户消息不能为空");
        }
        ChatSession session = command.session();
        ChatMessage parent = command.parentMessageId() == null ? null : requireMessageInSession(session, command.parentMessageId());
        String messageId = idGenerator.newId("msg", IdGenerateContext.of(command.tenantId(), command.userId(), session.id()));
        ChatMessage message = new ChatMessage(
                messageId,
                command.tenantId(),
                command.userId(),
                session.id(),
                command.parentMessageId(),
                nextNodeOrder(session),
                parent == null ? 0 : parent.treeDepth() + 1,
                nextSiblingIndex(command.tenantId(), command.userId(), session.id(), command.parentMessageId(), "user"),
                "user",
                command.content(),
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
        saveAttachments(saved, command.safeAttachments());
        sessionRepository.updateCurrentLeaf(command.tenantId(), command.userId(), session.id(), saved.id());
        return saved;
    }

    private long nextNodeOrder(ChatSession session) {
        return sessionRepository.nextNodeOrder(session.tenantId(), session.userId(), session.id());
    }

    private int nextSiblingIndex(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        return messageRepository.countSiblings(tenantId, userId, sessionId, parentMessageId, role) + 1;
    }

    private ChatMessage requireMessageInSession(ChatSession session, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        return messageRepository.findByOwnerAndId(session.tenantId(), session.userId(), messageId)
                .filter(message -> session.id().equals(message.sessionId()))
                .orElseThrow(() -> new IllegalArgumentException("消息不存在或不属于当前会话: " + messageId));
    }

    /**
     * 计算前端版本游标切换后的真实 leaf。
     *
     * <p>用户问题本身可能不是路径终点；如果它下面已经有回答，选择该问题版本时应把会话
     * leaf 落到最新回答，保持“问题 + 回答”成对展示。assistant 或其他角色消息本身就是 leaf。</p>
     */
    private String effectiveLeafForPathSelection(UserContext user, ChatSession session, ChatMessage selected) {
        if (!"user".equalsIgnoreCase(selected.role())) {
            return selected.id();
        }
        return messageRepository.findSiblings(user.tenantId(), user.ownerUserId(), session.id(), selected.id(), "assistant")
                .stream()
                .reduce((previous, current) -> current)
                .map(ChatMessage::id)
                .orElse(selected.id());
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
                    idGenerator.newId("msg_att", IdGenerateContext.of(message.tenantId(), message.userId(), message.sessionId())),
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

    private void copyAttachments(UserContext user, ChatMessage source, ChatMessage target) {
        int index = 0;
        for (ChatMessageAttachment attachment : messageRepository.findAttachments(user.tenantId(), user.ownerUserId(), source.id())) {
            messageRepository.saveAttachment(new ChatMessageAttachment(
                    idGenerator.newId("msg_att", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), target.sessionId())),
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private List<String> normalizeDeleteSessionIds(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            throw new IllegalArgumentException("sessionIds 不能为空");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String sessionId : sessionIds) {
            if (sessionId == null || sessionId.isBlank()) {
                continue;
            }
            normalized.add(sessionId.trim());
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("sessionIds 不能为空");
        }
        if (normalized.size() > MAX_BATCH_DELETE_SIZE) {
            throw new IllegalArgumentException("单次最多删除 " + MAX_BATCH_DELETE_SIZE + " 个会话");
        }
        return List.copyOf(normalized);
    }
}
