package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.ChatEventStore;
import com.huawei.it.ex.one.chat.application.repository.ChatRunCache;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunStopDecision;
import com.huawei.it.ex.one.chat.domain.ChatRunStopResult;
import com.huawei.it.ex.one.chat.domain.ChatStreamStatus;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Stable ChatRun application entry delegating lifecycle policy to a focused component. */
@Service
public class ChatRunApplicationService implements ChatRunQueryService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunApplicationService.class);

    private final ChatRunLifecycle lifecycle;

    @Autowired
    public ChatRunApplicationService(
            ChatRunRepository repository,
            ChatRunCache cache,
            PermissionChecker permissionChecker,
            SessionRepository sessionRepository,
            ChatStreamStatusService streamStatusService) {
        this.lifecycle = new ChatRunLifecycle(
                repository,
                cache,
                permissionChecker,
                sessionRepository,
                streamStatusService,
                log);
    }

    ChatRunApplicationService(
            ChatRunRepository repository,
            ChatRunCache cache,
            ChatEventStore eventStore,
            PermissionChecker permissionChecker,
            SessionRepository sessionRepository) {
        this(repository,
                cache,
                permissionChecker,
                sessionRepository,
                new ChatStreamStatusService(eventStore));
    }

    public ChatRun createRunning(CreateChatRunContext context) {
        return lifecycle.createRunning(context);
    }

    ChatRun insertRunning(CreateChatRunContext context) {
        return lifecycle.insertRunning(context);
    }

    public ChatRun createInteractionRunning(CreateChatRunContext context, String interactionId) {
        return lifecycle.createInteractionRunning(context, interactionId);
    }

    ChatRun insertInteractionRunning(CreateChatRunContext context, String interactionId) {
        return lifecycle.insertInteractionRunning(context, interactionId);
    }

    public ChatRun observeEvent(ChatEvent event) {
        return lifecycle.observeEvent(event);
    }

    public ChatRun bindAssistantMessage(String runId, String assistantMessageId) {
        return lifecycle.bindAssistantMessage(runId, assistantMessageId);
    }

    public ChatRun bindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding) {
        return lifecycle.bindResolvedRoute(runId, route, binding);
    }

    public ChatRun bindRuntimeProvider(String runId, String runtimeProvider) {
        return lifecycle.bindRuntimeProvider(runId, runtimeProvider);
    }

    public ChatRunStopDecision requestStop(UserContext user, String runId, String reason) {
        return lifecycle.requestStop(user, runId, reason);
    }

    public ChatRunStopResult toStopResult(ChatRun run) {
        return lifecycle.toStopResult(run);
    }

    public void synchronizeCommittedRunCache(ChatRun run) {
        lifecycle.synchronizeCommittedRunCache(run);
    }

    public ChatRun requireOwnedRun(UserContext user, String runId) {
        return lifecycle.requireOwnedRun(user, runId);
    }

    @Override
    public Map<String, ChatRun> findOwnedRunsByIds(
            UserContext user,
            Collection<String> runIds) {
        return lifecycle.findOwnedRunsByIds(user, runIds);
    }

    public void rejectIfActiveRunExists(UserContext user, String sessionId) {
        lifecycle.rejectIfActiveRunExists(user, sessionId);
    }

    public Optional<ChatRun> findActiveRun(UserContext user, String sessionId) {
        return lifecycle.findActiveRun(user, sessionId);
    }

    public boolean shouldAcceptEvent(ChatEvent event) {
        return lifecycle.shouldAcceptEvent(event);
    }

    @Override
    public ChatStreamStatus streamStatus(UserContext user, String sessionId) {
        return lifecycle.streamStatus(user, sessionId);
    }
}
