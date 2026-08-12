package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleAppExclusionProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleRequest;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/** 会话标题总结的异步旁路编排。 */
@Service
class SessionTitleApplicationService {
    private static final AppLogger log = AppLoggerFactory.getLogger(SessionTitleApplicationService.class);
    private static final String INTERACTION_ID_METADATA = "interactionId";
    private static final int MAX_QUERY_COUNT = 3;

    private final SessionTitleProperties properties;
    private final SessionTitleAppExclusionProvider appExclusionProvider;
    private final SessionTitleProvider provider;
    private final SessionTitleCommitService commitService;
    private final SessionTitleMetadata titleMetadata;
    private final SessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatRunRepository runRepository;
    private final Scheduler ioScheduler;
    private final Semaphore requestPermits;
    private final int maxConcurrentRequests;
    private final Duration requestTimeout;

    SessionTitleApplicationService(
            SessionTitleProperties properties,
            SessionTitleAppExclusionProvider appExclusionProvider,
            SessionTitleProvider provider,
            SessionTitleCommitService commitService,
            SessionTitleMetadata titleMetadata,
            SessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            ChatRunRepository runRepository,
            @Qualifier("sessionTitleIoScheduler") Scheduler ioScheduler) {
        this.properties = properties;
        this.appExclusionProvider = appExclusionProvider;
        this.provider = provider;
        this.commitService = commitService;
        this.titleMetadata = titleMetadata;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.runRepository = runRepository;
        this.ioScheduler = ioScheduler;
        this.maxConcurrentRequests = properties.normalizedMaxConcurrentRequests();
        this.requestPermits = new Semaphore(maxConcurrentRequests);
        this.requestTimeout = properties.effectiveRequestTimeout();
    }

    void schedule(UserContext user, ChatCommand command, ChatSession session,
                  ChatRunMessagePlan messagePlan, ChatRun run) {
        if (!eligibleTrigger(command, session, messagePlan, run)) {
            return;
        }
        Trigger trigger = new Trigger(
                user.tenantId(), user.ownerUserId(), session.id(), run.id(),
                messagePlan.userMessage().id(), properties.normalizeLanguage(command.language()));
        try {
            appExcluded(session.appId(), trigger)
                    .subscribeOn(ioScheduler)
                    .filter(excluded -> !excluded)
                    .flatMap(ignored -> Mono.fromCallable(() -> collectCandidate(trigger))
                            .subscribeOn(ioScheduler))
                    .flatMap(Mono::justOrEmpty)
                    .flatMap(candidate -> generateAndCommit(candidate)
                            .onErrorResume(ignored -> {
                                logFailure(trigger, candidate.queryCount());
                                return Mono.empty();
                            }))
                    .subscribe(ignored -> { }, failure -> logFailure(trigger, 0));
        } catch (RuntimeException ignored) {
            logFailure(trigger, 0);
        }
    }

    private Mono<Boolean> generateAndCommit(SessionTitleCandidate candidate) {
        return generateTitle(candidate)
                .flatMap(generated -> Mono.fromCallable(() -> commitService.apply(
                                generated.candidate(), generated.title()))
                        .subscribeOn(ioScheduler));
    }

    private Mono<GeneratedTitle> generateTitle(SessionTitleCandidate candidate) {
        return Mono.defer(() -> {
            if (!requestPermits.tryAcquire()) {
                logCapacityRejected(candidate);
                return Mono.empty();
            }
            SessionTitleRequest request = new SessionTitleRequest(
                    candidate.tenantId(), candidate.userId(), candidate.sessionId(),
                    candidate.queries(), candidate.language());
            /*
             * 许可覆盖同步鉴权、异步HTTP及响应解析，并在成功、异常、超时或取消时统一释放。
             * 标题提交事务位于该Publisher之后，不占用外部调用许可。
             */
            return Mono.defer(() -> provider.generate(request))
                    .timeout(requestTimeout)
                    .map(title -> new GeneratedTitle(candidate, normalizeTitle(title)))
                    .doFinally(ignored -> requestPermits.release());
        });
    }

    private boolean eligibleTrigger(ChatCommand command, ChatSession session,
                                    ChatRunMessagePlan messagePlan, ChatRun run) {
        if (!properties.isEnabled() || command == null || session == null || messagePlan == null || run == null) {
            return false;
        }
        if (command.runMode() != ChatRunMode.NEXT && command.runMode() != ChatRunMode.EDIT_USER) {
            return false;
        }
        ChatMessage userMessage = messagePlan.userMessage();
        if (userMessage == null || userMessage.content() == null || userMessage.content().isBlank()
                || userMessage.branchSnapshot()) {
            return false;
        }
        return titleMetadata.read(session.metadataJson())
                .map(state -> state.source().autoReplaceable())
                .orElse(false);
    }

    private Mono<Boolean> appExcluded(String appId, Trigger trigger) {
        return Mono.defer(() -> appExclusionProvider.isExcluded(appId))
                .defaultIfEmpty(false)
                .onErrorResume(ignored -> {
                    logAppExclusionFailure(trigger);
                    return Mono.just(false);
                });
    }

    private Optional<SessionTitleCandidate> collectCandidate(Trigger trigger) {
        ChatSession session = sessionRepository.findByTenantIdAndUserIdAndId(
                        trigger.tenantId(), trigger.userId(), trigger.sessionId())
                .orElse(null);
        SessionTitleSummaryState state = session == null
                ? null
                : titleMetadata.read(session.metadataJson()).orElse(null);
        if (state == null || !state.source().autoReplaceable()) {
            return Optional.empty();
        }
        List<ChatMessage> path = messageRepository.findPathNodesToMessage(
                trigger.tenantId(), trigger.userId(), trigger.sessionId(), trigger.userMessageId());
        Map<String, ChatRun> runs = runsById(trigger, path);
        List<ChatMessage> businessQueries = path.stream()
                .filter(this::isUserQuestion)
                .filter(message -> eligibleRun(runs.get(message.runId())))
                .toList();
        int currentIndex = indexOfMessage(businessQueries, trigger.userMessageId());
        // 前三问完整总结未成功时，后续有效问题仅作为重试触发器，发送内容仍限制为前三问。
        if (currentIndex < 0
                || (currentIndex >= MAX_QUERY_COUNT
                && state.appliedQueryCount() >= MAX_QUERY_COUNT)) {
            return Optional.empty();
        }
        List<String> queries = businessQueries.stream()
                .limit(MAX_QUERY_COUNT)
                .map(message -> message.content().trim())
                .toList();
        ChatMessage current = businessQueries.get(currentIndex);
        int queryCount = queries.size();
        long nodeOrder = current.nodeOrder() == null ? 0L : current.nodeOrder();
        if (!state.olderThan(queryCount, nodeOrder)) {
            return Optional.empty();
        }
        return Optional.of(new SessionTitleCandidate(
                trigger.tenantId(), trigger.userId(), trigger.sessionId(), trigger.runId(),
                queries, trigger.language(), queryCount, nodeOrder));
    }

    private Map<String, ChatRun> runsById(Trigger trigger, List<ChatMessage> path) {
        Collection<String> runIds = path.stream()
                .map(ChatMessage::runId)
                .filter(runId -> runId != null && !runId.isBlank())
                .distinct()
                .toList();
        Map<String, ChatRun> runs = new LinkedHashMap<>();
        runRepository.findByTenantIdAndUserIdAndIds(trigger.tenantId(), trigger.userId(), runIds)
                .forEach(run -> runs.put(run.id(), run));
        return runs;
    }

    private boolean isUserQuestion(ChatMessage message) {
        return message != null
                && "user".equals(message.role())
                && message.content() != null
                && !message.content().isBlank()
                && !message.branchSnapshot();
    }

    private boolean eligibleRun(ChatRun run) {
        return run != null
                && (run.runMode() == ChatRunMode.NEXT || run.runMode() == ChatRunMode.EDIT_USER)
                && !run.metadata().containsKey(INTERACTION_ID_METADATA);
    }

    private int indexOfMessage(List<ChatMessage> messages, String messageId) {
        for (int index = 0; index < messages.size(); index++) {
            if (messageId.equals(messages.get(index).id())) {
                return index;
            }
        }
        return -1;
    }

    private String normalizeTitle(String value) {
        if (value == null) {
            throw new IllegalStateException("Session title response does not contain title");
        }
        StringBuilder normalized = new StringBuilder();
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        String title = normalized.toString().trim();
        if (title.isEmpty()) {
            throw new IllegalStateException("Session title response contains a blank title");
        }
        int maxLength = properties.getMaxTitleLength();
        int codePoints = title.codePointCount(0, title.length());
        return codePoints <= maxLength
                ? title
                : title.substring(0, title.offsetByCodePoints(0, maxLength));
    }

    private void logFailure(Trigger trigger, int queryCount) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                        "Session title summary failed; keeping the current title")
                .runId(trigger.runId())
                .sessionId(trigger.sessionId())
                .operation("session-title.generate")
                .attribute("queryCount", queryCount)
                .retryable(false)
                .build());
    }

    private void logAppExclusionFailure(Trigger trigger) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                        "Session title app exclusion check failed; continuing title summary")
                .runId(trigger.runId())
                .sessionId(trigger.sessionId())
                .operation("session-title.app-exclusion")
                .retryable(false)
                .build());
    }

    private void logCapacityRejected(SessionTitleCandidate candidate) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RESOURCE_EXHAUSTED,
                        "Session title summary was skipped because the concurrent request limit was reached")
                .runId(candidate.runId())
                .sessionId(candidate.sessionId())
                .operation("session-title.bulkhead")
                .attribute("queryCount", candidate.queryCount())
                .attribute("maxConcurrentRequests", maxConcurrentRequests)
                .retryable(true)
                .build());
    }

    private record Trigger(
            String tenantId,
            String userId,
            String sessionId,
            String runId,
            String userMessageId,
            String language
    ) {
    }

    private record GeneratedTitle(SessionTitleCandidate candidate, String title) {
    }
}
