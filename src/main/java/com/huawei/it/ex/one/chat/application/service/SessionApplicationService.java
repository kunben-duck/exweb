package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.ChatMessageRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessagePage;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.ChatSessionNumberPage;
import com.huawei.it.ex.one.chat.domain.ChatSessionPage;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stable session application entry with focused query, mutation, and lifecycle collaborators. */
@Service
public class SessionApplicationService implements ChatSessionService {
    private final SessionMessageMutationService messageMutationService;
    private final SessionQueryService queryService;
    private final SessionLifecycleOperations lifecycle;

    @Autowired
    public SessionApplicationService(
            SessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            IdGenerator idGenerator,
            PermissionChecker permissionChecker,
            SessionDeleteRunSupport deleteRunSupport) {
        this.messageMutationService = new SessionMessageMutationService(
                sessionRepository, messageRepository, idGenerator);
        SessionBranchService branchService = new SessionBranchService(
                sessionRepository, messageRepository, idGenerator, messageMutationService);
        this.queryService = new SessionQueryService(
                sessionRepository, messageRepository, permissionChecker, messageMutationService);
        this.lifecycle = new SessionLifecycleOperations(
                sessionRepository,
                messageRepository,
                idGenerator,
                messageMutationService,
                branchService,
                queryService,
                deleteRunSupport);
    }

    public ChatSession loadOrCreate(ChatCommand command) {
        return lifecycle.loadOrCreate(command);
    }

    @Override
    public ChatSession createSession(UserContext user, String title, String channel) {
        return createSession(user, title, channel, null, null);
    }

    @Override
    public ChatSession createSession(
            UserContext user,
            String title,
            String channel,
            String appId,
            String appName) {
        return lifecycle.createSession(user, title, channel, appId, appName);
    }

    @Override
    public ChatSession getSession(UserContext user, String sessionId) {
        return queryService.getSession(user, sessionId);
    }

    @Override
    public ChatSession markSessionRead(UserContext user, String sessionId, long readThroughSeq) {
        return queryService.markSessionRead(user, sessionId, readThroughSeq);
    }

    @Override
    public ChatMessagePage listMessages(UserContext user, String sessionId, String cursor, int limit) {
        return listMessages(user, sessionId, null, cursor, limit);
    }

    @Override
    public ChatMessagePage listMessages(
            UserContext user,
            String sessionId,
            String leafMessageId,
            String cursor,
            int limit) {
        return queryService.listMessages(user, sessionId, leafMessageId, cursor, limit);
    }

    @Override
    public List<ChatMessage> listMessageTree(UserContext user, String sessionId) {
        return queryService.listMessageTree(user, sessionId);
    }

    @Override
    public List<ChatMessage> listMessageTreeNodes(UserContext user, String sessionId) {
        return queryService.listMessageTreeNodes(user, sessionId);
    }

    @Override
    public ChatSessionPage listSessions(UserContext user, String cursor, int limit) {
        return listSessions(user, null, cursor, limit);
    }

    @Override
    public ChatSessionPage listSessions(
            UserContext user,
            String appId,
            String cursor,
            int limit) {
        return queryService.listSessions(user, appId, cursor, limit);
    }

    @Override
    public ChatSessionNumberPage listSessionsByPage(
            UserContext user,
            int curPage,
            int pageSize) {
        return listSessionsByPage(user, null, curPage, pageSize);
    }

    @Override
    public ChatSessionNumberPage listSessionsByPage(
            UserContext user,
            String appId,
            int curPage,
            int pageSize) {
        return queryService.listSessionsByPage(user, appId, curPage, pageSize);
    }

    @Override
    public Map<String, String> findFirstAssistantAnswers(
            UserContext user,
            List<ChatSession> sessions) {
        return queryService.findFirstAssistantAnswers(user, sessions);
    }

    @Override
    public ChatSession renameSession(UserContext user, String sessionId, String title) {
        return lifecycle.renameSession(user, sessionId, title);
    }

    @Override
    public ChatSession archiveSession(UserContext user, String sessionId) {
        return lifecycle.archiveSession(user, sessionId);
    }

    @Override
    public ChatSession restoreSession(UserContext user, String sessionId) {
        return lifecycle.restoreSession(user, sessionId);
    }

    @Override
    public ChatSession deleteSession(UserContext user, String sessionId) {
        return deleteSessions(user, List.of(sessionId)).getFirst();
    }

    @Override
    @Transactional
    public List<ChatSession> deleteSessions(UserContext user, List<String> sessionIds) {
        return lifecycle.deleteSessions(user, sessionIds);
    }

    public ChatRunMessagePlan prepareRunMessage(
            UserContext user,
            ChatCommand command,
            ChatSession session,
            String runId,
            List<AttachmentRef> attachments) {
        return messageMutationService.prepareRunMessage(user, command, session, runId, attachments);
    }

    public ChatRunMessagePlan prepareIntentClarificationAnswer(
            IntentClarificationAnswerCommand command) {
        return messageMutationService.prepareIntentClarificationAnswer(command);
    }

    void lockForMessageMutation(String tenantId, String userId, ChatSession session) {
        lifecycle.lockForMessageMutation(tenantId, userId, session);
    }

    public void advanceLatestMessageSeq(UserContext user, ChatSession session, long messageSeq) {
        lifecycle.advanceLatestMessageSeq(user, session, messageSeq);
    }

    public ChatMessage saveUserMessage(ChatCommand command, ChatSession session) {
        return messageMutationService.saveUserMessage(command, session);
    }

    public ChatMessage saveAssistantMessage(AssistantMessageSaveCommand command) {
        return messageMutationService.saveAssistantMessage(command);
    }

    public ChatMessage updateAssistantMessage(AssistantMessageUpdateCommand command) {
        return messageMutationService.updateAssistantMessage(command);
    }

    @Override
    public List<ChatMessage> listVariants(UserContext user, String sessionId, String messageId) {
        return queryService.listVariants(user, sessionId, messageId);
    }

    @Override
    public ChatSession selectPath(UserContext user, String sessionId, String leafMessageId) {
        return lifecycle.selectPath(user, sessionId, leafMessageId);
    }

    @Override
    public ChatSession createBranch(
            UserContext user,
            String sessionId,
            String sourceMessageId,
            String title) {
        return lifecycle.createBranch(user, sessionId, sourceMessageId, title);
    }

    public void validateAppTag(
            UserContext user,
            String sessionId,
            String appId,
            String appName) {
        lifecycle.validateAppTag(user, sessionId, appId, appName);
    }
}
