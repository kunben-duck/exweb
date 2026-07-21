package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.ChatRunCache;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.ActiveRunExistsException;
import com.huawei.it.ex.one.chat.domain.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunCancelSignal;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import com.huawei.it.ex.one.chat.domain.ChatRunStopDecision;
import com.huawei.it.ex.one.chat.domain.ChatRunStopResult;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.ChatStreamStatus;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Package-local lifecycle, ownership, and cache policy for ChatRun facts. */
final class ChatRunLifecycle {
    private static final String SESSION_STATUS_ACTIVE = "ACTIVE";
    private static final String SESSION_STATUS_DELETED = "DELETED";

    private final ChatRunRepository repository;
    private final ChatRunCache cache;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;
    private final ChatStreamStatusService streamStatusService;
    private final AppLogger log;

    ChatRunLifecycle(
            ChatRunRepository repository,
            ChatRunCache cache,
            PermissionChecker permissionChecker,
            SessionRepository sessionRepository,
            ChatStreamStatusService streamStatusService,
            AppLogger log) {
        this.repository = repository;
        this.cache = cache;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
        this.streamStatusService = streamStatusService;
        this.log = log;
    }

    ChatRun createRunning(CreateChatRunContext context) {
        UserContext user = context.user();
        ensureOwnedActiveSession(user, context.sessionId());
        rejectIfActiveRunExists(user, context.sessionId());
        ChatRun saved = insertRunning(context);
        cache.putActive(saved);
        return saved;
    }

    ChatRun insertRunning(CreateChatRunContext context) {
        ensureOwnedActiveSession(context.user(), context.sessionId());
        return repository.insert(newRunning(context));
    }

    ChatRun createInteractionRunning(CreateChatRunContext context, String interactionId) {
        UserContext user = context.user();
        ensureOwnedActiveSession(user, context.sessionId());
        rejectIfActiveRunExists(user, context.sessionId());
        ChatRun saved = insertInteractionRunning(context, interactionId);
        cache.putActive(saved);
        return saved;
    }

    ChatRun insertInteractionRunning(CreateChatRunContext context, String interactionId) {
        ensureOwnedActiveSession(context.user(), context.sessionId());
        ChatRun run = newRunning(context);
        return repository.insertInteractionContinuationIfClaimed(run, interactionId)
                .orElseThrow(() -> ChatInteractionUnavailableException.alreadyHandled(interactionId));
    }

    ChatRun observeEvent(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return null;
        }
        if (highFrequencyMessageEvent(event)) {
            return null;
        }
        Optional<ChatRun> current = repository.findById(event.runId());
        if (current.isEmpty()) {
            return null;
        }
        ChatRun run = current.get();
        if (lifecycleClosedFor(run, event)) {
            return run;
        }
        ChatRun next = advanceLifecycle(run, event);
        return save(updateRuntimeSession(next, event));
    }

    ChatRun bindAssistantMessage(String runId, String assistantMessageId) {
        return repository.findById(runId)
                .map(run -> save(run.withAssistantMessageId(assistantMessageId)))
                .orElse(null);
    }

    ChatRun bindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding) {
        if (runId == null || runId.isBlank() || route == null) {
            return null;
        }
        return repository.findById(runId)
                .map(run -> save(run.withResolvedRoute(
                        route.type() == null ? null : route.type().name(),
                        route.selectedAgentCode(),
                        binding == null ? null : binding.provider(),
                        binding == null ? null : binding.runtimeSessionId())))
                .orElse(null);
    }

    ChatRun bindRuntimeProvider(String runId, String runtimeProvider) {
        if (runId == null || runId.isBlank() || runtimeProvider == null || runtimeProvider.isBlank()) {
            return null;
        }
        return repository.findById(runId)
                .map(run -> save(run.withResolvedRoute(
                        run.routeType(),
                        run.agentCode(),
                        runtimeProvider.trim(),
                        run.runtimeSessionId())))
                .orElse(null);
    }

    ChatRunStopDecision requestStop(UserContext user, String runId, String reason) {
        ChatRun run = requireOwnedRun(user, runId);
        if (!run.stopRetryable()) {
            return new ChatRunStopDecision(run, false);
        }
        if (run.status() == ChatRunStatus.CANCELLING) {
            cache.markCancellationRequested(runId);
            return new ChatRunStopDecision(run, true);
        }
        String effectiveReason = reason == null || reason.isBlank() ? "USER_STOP" : reason;
        repository.tryMarkCancelling(new ChatRunRepository.StopClaim(
                run.id(), user.tenantId(), user.ownerUserId(), effectiveReason, Instant.now()));
        ChatRun latest = requireOwnedRun(user, runId);
        if (latest.status() != ChatRunStatus.CANCELLING) {
            return new ChatRunStopDecision(latest, false);
        }
        cache.markCancellationRequested(runId);
        cache.putActive(latest);
        return new ChatRunStopDecision(latest, true);
    }

    ChatRunStopResult toStopResult(ChatRun run) {
        ChatRun latest = repository.findById(run.id()).orElse(run);
        String assistantMessageId = latest.assistantMessageId();
        boolean messageReady = assistantMessageId != null && !assistantMessageId.isBlank();
        return new ChatRunStopResult(
                latest.id(),
                latest.sessionId(),
                latest.status(),
                latest.lastSeq() == null ? 0L : latest.lastSeq(),
                latest.finishedAt() == null ? Instant.now() : latest.finishedAt(),
                messageReady,
                messageReady ? assistantMessageId : null,
                messageReady ? assistantMessageId : null);
    }

    void synchronizeCommittedRunCache(ChatRun run) {
        if (run == null) {
            return;
        }
        try {
            if (run.status().terminal()) {
                cache.evictActive(run.tenantId(), run.userId(), run.sessionId());
            } else {
                cache.putActive(run);
            }
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(
                            SystemErrorCode.REDIS_CACHE_SYNC_FAILED,
                            "ChatRun database state committed but active-run cache synchronization failed")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.cache.after-commit")
                    .attribute("runStatus", run.status())
                    .build(), ex);
        }
    }

    ChatRun requireOwnedRun(UserContext user, String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        return repository.findByTenantIdAndUserIdAndId(user.tenantId(), user.ownerUserId(), runId)
                .orElseThrow(() -> new SecurityException("run 不存在或不属于当前用户"));
    }

    Map<String, ChatRun> findOwnedRunsByIds(UserContext user, Collection<String> runIds) {
        permissionChecker.checkChatPermission(user);
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        List<String> normalizedRunIds = runIds.stream()
                .filter(runId -> runId != null && !runId.isBlank())
                .distinct()
                .toList();
        if (normalizedRunIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ChatRun> runs = new LinkedHashMap<>();
        repository.findByTenantIdAndUserIdAndIds(
                        user.tenantId(), user.ownerUserId(), normalizedRunIds)
                .forEach(run -> runs.putIfAbsent(run.id(), run));
        return Map.copyOf(runs);
    }

    void rejectIfActiveRunExists(UserContext user, String sessionId) {
        findActive(user.tenantId(), user.ownerUserId(), sessionId)
                .ifPresent(active -> {
                    throw new ActiveRunExistsException(sessionId, active.id());
                });
    }

    Optional<ChatRun> findActiveRun(UserContext user, String sessionId) {
        ensureOwnedSession(user, sessionId);
        return findActive(user.tenantId(), user.ownerUserId(), sessionId);
    }

    boolean shouldAcceptEvent(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return false;
        }
        if ("run.cancelled".equals(event.type())) {
            return repository.findById(event.runId())
                    .map(run -> run.status() == ChatRunStatus.RUNNING
                            || run.status() == ChatRunStatus.CANCELLING)
                    .orElse(false);
        }
        if (cache.cancellationSignal(event.runId()) == ChatRunCancelSignal.REQUESTED) {
            return false;
        }
        if ("run.completed".equals(event.type())
                || "run.failed".equals(event.type())
                || "run.waiting_user".equals(event.type())) {
            return repository.findById(event.runId())
                    .map(run -> run.status() == ChatRunStatus.RUNNING)
                    .orElse(false);
        }
        return true;
    }

    ChatStreamStatus streamStatus(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        ensureOwnedSession(user, sessionId);
        return streamStatusService.streamStatus(
                user,
                sessionId,
                () -> findActive(user.tenantId(), user.ownerUserId(), sessionId));
    }

    private ChatRun newRunning(CreateChatRunContext context) {
        UserContext user = context.user();
        Instant now = Instant.now();
        return new ChatRun(
                context.runId(),
                user.tenantId(),
                user.ownerUserId(),
                context.sessionId(),
                ChatRunStatus.RUNNING,
                context.route() == null || context.route().type() == null
                        ? null
                        : context.route().type().name(),
                context.route() == null ? null : context.route().selectedAgentCode(),
                context.binding() == null ? null : context.binding().provider(),
                context.binding() == null ? null : context.binding().runtimeSessionId(),
                context.runMode(),
                context.parentMessageId(),
                context.userMessageId(),
                null,
                null,
                null,
                null,
                now,
                null,
                context.safeMetadata(),
                now,
                now);
    }

    private boolean highFrequencyMessageEvent(ChatEvent event) {
        return "message.delta".equals(event.type())
                || "message.snapshot".equals(event.type())
                || "message.completed".equals(event.type());
    }

    private boolean lifecycleClosedFor(ChatRun run, ChatEvent event) {
        return run.status().terminal()
                || run.status() == ChatRunStatus.CANCELLING
                && !"run.cancelled".equals(event.type())
                && !"run.failed".equals(event.type());
    }

    private ChatRun advanceLifecycle(ChatRun run, ChatEvent event) {
        return switch (event.type()) {
            case "run.started" -> run.withFirstSeq(event.sequence());
            case "run.completed" -> run.completed(event.sequence());
            case "run.waiting_user" -> run.waitingUser(event.sequence());
            case "run.failed" -> run.failed(event.sequence());
            case "run.cancelled" -> run.cancelled(event.sequence());
            default -> run.withLastSeq(event.sequence());
        };
    }

    private ChatRun updateRuntimeSession(ChatRun run, ChatEvent event) {
        Object runtimeSessionId = event.payload() == null ? null : event.payload().get("runtimeSessionId");
        if (runtimeSessionId != null
                && !String.valueOf(runtimeSessionId).isBlank()
                && !String.valueOf(runtimeSessionId).equals(run.runtimeSessionId())) {
            return run.withRuntimeSessionId(String.valueOf(runtimeSessionId));
        }
        return run;
    }

    private Optional<ChatRun> findActive(String tenantId, String userId, String sessionId) {
        Optional<ChatRun> cached = cache.getActive(tenantId, userId, sessionId);
        if (cached.isPresent()) {
            Optional<ChatRun> persisted = repository.findById(cached.get().id());
            if (persisted.isPresent() && !persisted.get().status().terminal()) {
                cache.putActive(persisted.get());
                return persisted;
            }
            cache.evictActive(tenantId, userId, sessionId);
        }
        Optional<ChatRun> persisted = repository.findActiveBySession(tenantId, userId, sessionId);
        persisted.ifPresent(cache::putActive);
        return persisted;
    }

    private ChatRun save(ChatRun run) {
        ChatRun saved = repository.save(run);
        if (saved.status().terminal()) {
            cache.evictActive(saved.tenantId(), saved.userId(), saved.sessionId());
        } else {
            cache.putActive(saved);
        }
        return saved;
    }

    private void ensureOwnedSession(UserContext user, String sessionId) {
        loadOwnedNotDeletedSession(user, sessionId);
    }

    private void ensureOwnedActiveSession(UserContext user, String sessionId) {
        ChatSession session = loadOwnedNotDeletedSession(user, sessionId);
        if (!SESSION_STATUS_ACTIVE.equals(session.status())) {
            throw new IllegalStateException("会话不可用: " + sessionId);
        }
    }

    private ChatSession loadOwnedNotDeletedSession(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        ChatSession session = sessionRepository.findByTenantIdAndUserIdAndId(
                        user.tenantId(), user.ownerUserId(), sessionId)
                .orElseThrow(() -> new SecurityException("会话不存在或不属于当前用户"));
        if (SESSION_STATUS_DELETED.equals(session.status())) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return session;
    }
}
