package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.facade.FinanceChatFacade;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.integration.trace.TraceContextProvider;
import com.huawei.it.ex.one.application.service.chat.ChatFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.it.ex.one.application.service.chat.MessageFeedbackCommand;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessageFeedback;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatStreamStatus;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatAgentModeDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatAgentModeSelectionDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatRunStartDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatRunStopDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatStreamStatusDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ConversationTurnStreamDto;
import com.huawei.it.ex.one.interfaces.chat.dto.CreateChatRunRequest;
import com.huawei.it.ex.one.interfaces.chat.dto.MessageFeedbackDto;
import com.huawei.it.ex.one.interfaces.chat.dto.MessageFeedbackRequest;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天 HTTP 接口。
 *
 * <p>正式版采用单一对话流协议：{@code POST /runs} 只创建后台 run 并返回
 * 订阅信息；本页新建 run 的实时输出由 WebSocket subscribe 承载；恢复已经存在的 active run
 * 时使用 run 级事件恢复先补发历史事件，再接续 live 事件直到 run 终态。</p>
 */
@RestController
@RequestMapping("/v1/chat")
@Validated
public class ChatController {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatController.class);

    private final FinanceChatFacade chatFacade;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunApplicationService chatRunService;
    private final ChatFeedbackApplicationService feedbackService;
    private final AuthContextProvider auth;
    private final TraceContextProvider traceContextProvider;
    private final PermissionChecker permissionChecker;
    private final ChatRequestTranslator requestTranslator;
    private final ChatEventTranslator eventTranslator;
    private final ChatTurnStreamTranslator turnStreamTranslator;
    private final RuntimeForwardHeaderExtractor forwardHeaderExtractor;
    private final ChatStreamProperties chatStreamProperties;
    public ChatController(FinanceChatFacade chatFacade, ChatStreamApplicationService chatStreamService,
                          ChatRunApplicationService chatRunService, ChatFeedbackApplicationService feedbackService,
                          AuthContextProvider auth, TraceContextProvider traceContextProvider,
                          PermissionChecker permissionChecker,
                          ChatRequestTranslator requestTranslator, ChatEventTranslator eventTranslator,
                          ChatTurnStreamTranslator turnStreamTranslator,
                          RuntimeForwardHeaderExtractor forwardHeaderExtractor,
                          ChatStreamProperties chatStreamProperties) {
        this.chatFacade = chatFacade;
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.feedbackService = feedbackService;
        this.auth = auth;
        this.traceContextProvider = traceContextProvider;
        this.permissionChecker = permissionChecker;
        this.requestTranslator = requestTranslator;
        this.eventTranslator = eventTranslator;
        this.turnStreamTranslator = turnStreamTranslator;
        this.forwardHeaderExtractor = forwardHeaderExtractor;
        this.chatStreamProperties = chatStreamProperties;
    }

    /**
     * 唯一提问入口：创建后台 run，并返回本轮运行标识和 run 级 stream topic。
     *
     * <p>WebSocket、Event Resume 与 stop 的 URL 属于前端 SDK/网关配置，
     * 不随每次 run 创建结果返回，避免后端业务响应承担客户端路由配置职责。</p>
     *
     * @param request 前端提问请求，只包含会话、用户文本、附件引用和 metadata；租户与用户由服务端身份上下文解析。
     * @param cookieHeader 原始 HTTP Cookie 头；只会作为内存快照透传给可信下游 adapter。
     * @return 新建后台 run 的创建结果，包含 runId、sessionId、firstSeq 和 streamTopicId。
     */
    @PostMapping(value = "/runs")
    public Mono<ChatRunStartDto> startRun(@Valid @RequestBody CreateChatRunRequest request,
                                          @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader) {
        UserContext user = resolveChatUser();
        TraceContext traceContext = resolveTraceContext();
        RuntimeForwardHeaders forwardHeaders = forwardHeaderExtractor.fromCookieHeader(cookieHeader);
        return chatFacade.startRun(user, traceContext, requestTranslator.toCommand(request), forwardHeaders)
                .map(runStart -> new ChatRunStartDto(
                        runStart.runId(),
                        runStart.sessionId(),
                        runStart.firstSeq(),
                        runStart.createdAt(),
                        runStart.streamTopicId()
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 停止指定 run 的当前回答。
     *
     * @param runId 需要停止的 run 标识；服务端会校验该 run 必须属于当前用户。
     * @param cookieHeader 原始 HTTP Cookie 头；只会用于可信下游 adapter 的尽力取消请求。
     * @return stop 后的 run 状态；已终态 run 会幂等返回当前状态。
     */
    @PostMapping(value = "/runs/{runId}/stop")
    public Mono<ChatRunStopDto> stopRun(@PathVariable("runId") String runId,
                                        @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader) {
        UserContext user = resolveChatUser();
        TraceContext traceContext = resolveTraceContext();
        RuntimeForwardHeaders forwardHeaders = forwardHeaderExtractor.fromCookieHeader(cookieHeader);
        return chatFacade.stopRun(user, traceContext, runId, forwardHeaders)
                .map(this::toStopDto)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 提交当前用户对 assistant 消息的反馈。
     *
     * @param messageId 被反馈的 assistant 消息标识；服务端会校验消息归属和角色。
     * @param request 反馈内容，包括 rating、原因编码、补充说明和可选 runId。
     * @return 已保存的反馈摘要。
     */
    @PostMapping(value = "/messages/{messageId}/feedback")
    public Mono<MessageFeedbackDto> feedback(@PathVariable("messageId") String messageId,
                                                  @RequestBody MessageFeedbackRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatMessageFeedback feedback = feedbackService.submit(user, new MessageFeedbackCommand(
                            messageId,
                            request == null ? null : request.runId(),
                            request == null ? null : request.rating(),
                            request == null ? null : request.reasonCode(),
                            request == null ? null : request.commentText(),
                            request == null ? null : request.metadata()
                    ));
                    return toFeedbackDto(feedback);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 取消当前用户对 assistant 消息的点赞或点踩。
     *
     * @param messageId 被取消反馈的 assistant 消息标识；服务端会校验消息归属和角色。
     * @param runId 可选 run 标识；存在时必须与消息属于同一会话。
     * @return 取消后的反馈状态摘要。
     */
    @DeleteMapping(value = "/messages/{messageId}/feedback")
    public Mono<MessageFeedbackDto> cancelFeedback(@PathVariable("messageId") String messageId,
                                                   @RequestParam(value = "runId", required = false) String runId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toFeedbackDto(feedbackService.cancel(user, messageId, runId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 会话级事件恢复接口，只根据 sessionId 与 afterSeq 读取缺失事件。
     *
     * <p>接口路径使用 resume 表达“恢复已落库事件流”的业务语义；响应仍然使用
     * {@code text/event-stream} 传输格式，便于浏览器边读边渲染。</p>
     *
     * @param sessionId 需要恢复事件的聊天会话标识；服务端会校验会话归属。
     * @param afterSeq 前端已经处理到的最大事件序号，只返回大于该值的事件。
     * @return 事件恢复流，event name 固定为 conversation-turn-stream，真实 ChatEventDto 位于 encodedItem.data。
     */
    @GetMapping(value = "/sessions/{sessionId}/events/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ConversationTurnStreamDto>>> resumeSessionEvents(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq) {
        UserContext user = resolveChatUser();
        Flux<ServerSentEvent<ConversationTurnStreamDto>> events = chatStreamService.resumeSession(user, sessionId, afterSeq)
                .map(eventTranslator::toDto)
                .map(turnStreamTranslator::streamItem)
                .map(this::toTurnStreamSse);
        return sseResponse(events);
    }

    /**
     * Run 级事件恢复接口，只恢复指定 run 的事件。
     *
     * <p>跨电脑打开同一会话时，如果 stream-status 中存在 activeRunId，前端应优先使用
     * 该接口从 activeRunFirstSeq 之前补齐当前回答已经生成的 event。若 run 仍未终止，
     * 该事件恢复连接会继续接入 live topic 并持续输出到 run 终态；不要再对同一个 run
     * 发 WebSocket subscribe。</p>
     *
     * @param runId 需要恢复事件的 run 标识；服务端会校验 run 归属。
     * @param afterSeq 前端已经处理到的最大事件序号，只发送大于该值的事件。
     * @return 事件恢复流，event name 固定为 conversation-turn-stream；active run 会持续到终态并发送 done。
     */
    @GetMapping(value = "/runs/{runId}/events/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ConversationTurnStreamDto>>> resumeRunEvents(
            @PathVariable("runId") String runId,
            @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq) {
        UserContext user = resolveChatUser();
        Flux<ChatEventDto> events = chatStreamService.resumeRun(user, runId, afterSeq)
                .map(eventTranslator::toDto);
        return sseResponse(withTurnHeartbeatAndDone(events)
                .map(this::toTurnStreamSse));
    }

    /**
     * 查询会话当前事件进度，前端重开页面后可先读取 latestSeq 再决定是否补发。
     *
     * @param sessionId 需要查询流式状态的聊天会话标识；服务端会校验会话归属。
     * @return 当前 latestSeq、activeRunId、activeStreamTopicId 和 cancellable 状态。
     */
    @GetMapping(value = "/sessions/{sessionId}/stream-status")
    public Mono<ChatStreamStatusDto> streamStatus(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toStreamStatusDto(chatRunService.streamStatus(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private TraceContext resolveTraceContext() {
        try {
            TraceContext context = traceContextProvider == null ? null : traceContextProvider.resolve();
            return context == null ? TraceContext.empty() : context;
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "TraceContext provider failed at request entry; continuing without traceId")
                    .operation("trace.resolve")
                    .retryable(false)
                    .build(), ex);
            return TraceContext.empty();
        }
    }

    private ResponseEntity<Flux<ServerSentEvent<ConversationTurnStreamDto>>> sseResponse(
            Flux<ServerSentEvent<ConversationTurnStreamDto>> events) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(events);
    }

    private ServerSentEvent<ConversationTurnStreamDto> toTurnStreamSse(ConversationTurnStreamDto item) {
        return ServerSentEvent.<ConversationTurnStreamDto>builder()
                .event(ChatTurnStreamTranslator.TURN_STREAM_TYPE)
                .data(item)
                .build();
    }

    private Flux<ConversationTurnStreamDto> withTurnHeartbeatAndDone(Flux<ChatEventDto> events) {
        Duration interval = chatStreamProperties.normalizedTurnHeartbeatInterval();
        AtomicLong lastSeq = new AtomicLong(0);
        AtomicReference<String> lastSessionId = new AtomicReference<>();
        AtomicReference<String> lastRunId = new AtomicReference<>();
        AtomicReference<String> terminalType = new AtomicReference<>();
        Flux<ChatEventDto> trackedEvents = events.doOnNext(dto -> {
            lastSeq.set(dto.sequence());
            lastSessionId.set(dto.sessionId());
            lastRunId.set(dto.runId());
            if (turnStreamTranslator.isTerminal(dto)) {
                terminalType.set(dto.type());
            }
        });
        return trackedEvents.publish(shared -> {
            Flux<ConversationTurnStreamDto> streamItems = shared.map(turnStreamTranslator::streamItem);
            Flux<ConversationTurnStreamDto> heartbeat = turnHeartbeat(interval, lastSessionId, lastRunId, lastSeq)
                    .takeUntilOther(shared.ignoreElements());
            Flux<ConversationTurnStreamDto> merged = interval.isZero() || interval.isNegative()
                    ? streamItems
                    : Flux.merge(streamItems, heartbeat);
            return merged.concatWith(Mono.defer(() -> {
                String terminal = terminalType.get();
                String sessionId = lastSessionId.get();
                String runId = lastRunId.get();
                if (terminal == null || sessionId == null || runId == null) {
                    return Mono.empty();
                }
                return Mono.just(turnStreamTranslator.done(sessionId, runId, lastSeq.get(), terminal));
            }));
        });
    }

    private Flux<ConversationTurnStreamDto> turnHeartbeat(Duration interval,
                                                          AtomicReference<String> sessionId,
                                                          AtomicReference<String> runId,
                                                          AtomicLong lastSeq) {
        if (interval.isZero() || interval.isNegative()) {
            return Flux.empty();
        }
        return Flux.interval(interval)
                .filter(ignored -> sessionId.get() != null && runId.get() != null)
                .map(ignored -> turnStreamTranslator.heartbeat(sessionId.get(), runId.get(), lastSeq.get()));
    }

    private ChatRunStopDto toStopDto(ChatRunStopResult result) {
        return new ChatRunStopDto(
                result.runId(),
                result.sessionId(),
                result.status().name(),
                result.latestSeq(),
                result.stoppedAt(),
                result.messageReady(),
                result.assistantMessageId(),
                result.feedbackTargetMessageId(),
                result.waitingUserInput(),
                result.interactionId(),
                result.interactionStatus(),
                result.interactionCancelledAt(),
                result.effectiveRunId()
        );
    }

    private ChatStreamStatusDto toStreamStatusDto(ChatStreamStatus status) {
        return new ChatStreamStatusDto(
                status.sessionId(),
                status.latestSeq(),
                status.activeRunId(),
                status.activeRunStatus() == null ? null : status.activeRunStatus().name(),
                status.activeStreamTopicId(),
                status.activeRunFirstSeq(),
                status.activeRunLastSeq(),
                status.cancellable(),
                status.waitingUserInput(),
                status.waitingSourceRunId(),
                status.interactionId(),
                status.interactionType(),
                status.assistantMessageId(),
                status.expiresAt(),
                status.autoSelectAt(),
                status.autoSelectTimeoutMs(),
                status.autoActionAt(),
                status.autoActionTimeoutMs(),
                status.autoActionType(),
                status.bindingProvider(),
                status.bindingTargetType(),
                status.bindingTargetId(),
                status.bindingIntentCode(),
                status.bindingIntentName(),
                status.bindingRouteSource(),
                status.bindingUpdatedAt(),
                toAgentModeDto(status.bindingAgentMode()),
                status.activeRunPhase(),
                status.asyncExpiresAt()
        );
    }

    private ChatAgentModeDto toAgentModeDto(com.huawei.it.ex.one.domain.runtime.AgentModeProfile profile) {
        if (profile == null) {
            return null;
        }
        return new ChatAgentModeDto(profile.selections().stream()
                .map(selection -> new ChatAgentModeSelectionDto(
                        selection.scheme(), selection.code(), selection.displayName()))
                .toList());
    }

    private MessageFeedbackDto toFeedbackDto(ChatMessageFeedback feedback) {
        return new MessageFeedbackDto(
                feedback.id(),
                feedback.messageId(),
                feedback.runId(),
                feedback.rating(),
                feedback.status(),
                feedback.reasonCode(),
                feedback.commentText(),
                feedback.metadata(),
                feedback.createdAt(),
                feedback.updatedAt()
        );
    }
}
