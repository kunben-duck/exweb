package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.service.ChatFeedbackApplicationService;
import com.huawei.finance.front.one.application.service.ChatRunApplicationService;
import com.huawei.finance.front.one.application.service.ChatStreamApplicationService;
import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessageFeedback;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatStreamStatus;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.finance.front.one.interfaces.chat.dto.MessageFeedbackDto;
import com.huawei.finance.front.one.interfaces.chat.dto.MessageFeedbackRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatRunRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatRunStartDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatRunStopDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatStreamStatusDto;
import com.huawei.finance.front.one.interfaces.chat.dto.RetryChatRunRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天 HTTP 接口。
 *
 * <p>正式版采用 ChatGPT-like 单一对话流协议：{@code POST /runs} 只创建后台 run 并返回
 * 订阅信息；实时输出由 WebSocket subscribe 承载；SSE 仅用于断线、刷新或复制页签后的
 * {@code afterSeq} 补发。</p>
 */
@RestController
@RequestMapping("/api/v1/ex/chat")
public class ChatController {
    private final FinanceChatFacade chatFacade;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunApplicationService chatRunService;
    private final ChatFeedbackApplicationService feedbackService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final ChatRequestTranslator requestTranslator;
    private final ChatEventTranslator eventTranslator;
    public ChatController(FinanceChatFacade chatFacade, ChatStreamApplicationService chatStreamService,
                          ChatRunApplicationService chatRunService, ChatFeedbackApplicationService feedbackService,
                          AuthContextProvider auth, PermissionChecker permissionChecker,
                          ChatRequestTranslator requestTranslator, ChatEventTranslator eventTranslator) {
        this.chatFacade = chatFacade;
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.feedbackService = feedbackService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.requestTranslator = requestTranslator;
        this.eventTranslator = eventTranslator;
    }

    /**
     * 唯一提问入口：创建后台 run，并返回本轮运行标识和 run 级 stream topic。
     *
     * <p>WebSocket、SSE resume 与 stop 的 URL 属于前端 SDK/网关配置，
     * 不随每次 run 创建结果返回，避免后端业务响应承担客户端路由配置职责。</p>
     *
     * @param request 前端提问请求，只包含会话、用户文本、附件引用和 metadata；租户与用户由服务端身份上下文解析。
     * @return 新建后台 run 的创建结果，包含 runId、sessionId、firstSeq 和 streamTopicId。
     */
    @PostMapping(value = "/runs")
    public Mono<ChatRunStartDto> startRun(@RequestBody CreateChatRunRequest request) {
        UserContext user = resolveChatUser();
        return chatFacade.startRun(user, requestTranslator.toCommand(request))
                .map(runStart -> new ChatRunStartDto(
                        runStart.runId(),
                        runStart.sessionId(),
                        runStart.firstSeq(),
                        runStart.createdAt(),
                        runStart.streamTopicId()
                ));
    }

    /**
     * 停止指定 run 的当前回答。
     *
     * @param runId 需要停止的 run 标识；服务端会校验该 run 必须属于当前用户。
     * @return stop 后的 run 状态；已终态 run 会幂等返回当前状态。
     */
    @PostMapping(value = "/runs/{runId}/stop")
    public Mono<ChatRunStopDto> stopRun(@PathVariable String runId) {
        UserContext user = resolveChatUser();
        return chatFacade.stopRun(user, runId)
                .map(this::toStopDto)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 基于原 run 所属会话重新生成回答。
     *
     * @param runId 被重试的原 run 标识；新回答会创建新的 run，不覆盖旧 run 事件。
     * @param request 重试请求；message 为空时复用原会话最近一条用户消息。
     * @return 新 run 的创建结果。
     */
    @PostMapping(value = "/runs/{runId}/retry")
    public Mono<ChatRunStartDto> retryRun(@PathVariable String runId,
                                                 @RequestBody(required = false) RetryChatRunRequest request) {
        UserContext user = resolveChatUser();
        return chatFacade.retryRun(user, runId, requestTranslator.toRetryCommand(request))
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
     * 提交当前用户对 assistant 消息的反馈。
     *
     * @param messageId 被反馈的 assistant 消息标识；服务端会校验消息归属和角色。
     * @param request 反馈内容，包括 rating、原因编码、补充说明和可选 runId。
     * @return 已保存的反馈摘要。
     */
    @PostMapping(value = "/messages/{messageId}/feedback")
    public Mono<MessageFeedbackDto> feedback(@PathVariable String messageId,
                                                  @RequestBody MessageFeedbackRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatMessageFeedback feedback = feedbackService.submit(
                            user,
                            messageId,
                            request == null ? null : request.runId(),
                            request == null ? null : request.rating(),
                            request == null ? null : request.reasonCode(),
                            request == null ? null : request.commentText(),
                            request == null ? null : request.metadata()
                    );
                    return new MessageFeedbackDto(feedback.id(), feedback.messageId(), feedback.runId(),
                            feedback.rating(), feedback.createdAt());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * SSE 断线补发/续传接口，只根据 sessionId 与 afterSeq 读取缺失事件。
     *
     * @param sessionId 需要恢复事件的聊天会话标识；服务端会校验会话归属。
     * @param afterSeq 前端已经处理到的最大事件序号，只返回大于该值的事件。
     * @return SSE 事件流，event name 等于聊天事件 type，data 为 ChatEventDto。
     */
    @GetMapping(value = "/sessions/{sessionId}/events/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEventDto>> resumeSse(@PathVariable String sessionId,
                                                             @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq) {
        UserContext user = resolveChatUser();
        return chatStreamService.resumeSession(user, sessionId, afterSeq)
                .map(eventTranslator::toDto)
                .map(dto -> ServerSentEvent.<ChatEventDto>builder().event(dto.type()).data(dto).build());
    }

    /**
     * 查询会话当前事件进度，前端重开页面后可先读取 latestSeq 再决定是否补发。
     *
     * @param sessionId 需要查询流式状态的聊天会话标识；服务端会校验会话归属。
     * @return 当前 latestSeq、activeRunId、activeStreamTopicId 和 cancellable 状态。
     */
    @GetMapping(value = "/sessions/{sessionId}/stream-status")
    public Mono<ChatStreamStatusDto> streamStatus(@PathVariable String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toStreamStatusDto(chatRunService.streamStatus(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private ChatRunStopDto toStopDto(ChatRunStopResult result) {
        return new ChatRunStopDto(
                result.runId(),
                result.sessionId(),
                result.status().name(),
                result.latestSeq(),
                result.stoppedAt()
        );
    }

    private ChatStreamStatusDto toStreamStatusDto(ChatStreamStatus status) {
        return new ChatStreamStatusDto(
                status.sessionId(),
                status.latestSeq(),
                status.activeRunId(),
                status.activeRunStatus() == null ? null : status.activeRunStatus().name(),
                status.activeStreamTopicId(),
                status.cancellable()
        );
    }
}
