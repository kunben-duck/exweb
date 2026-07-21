package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.application.service.ChatApplicationService;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.chat.domain.ChatRunStopResult;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatRunStartDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatRunStopDto;
import com.huawei.it.ex.one.chat.interfaces.dto.CreateChatRunRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final ChatApplicationService chatService;
    private final ChatRequestTranslator requestTranslator;
    private final ChatRequestContextResolver requestContextResolver;

    @Autowired
    public ChatController(ChatApplicationService chatService,
                          ChatRequestTranslator requestTranslator,
                          ChatRequestContextResolver requestContextResolver) {
        this.chatService = chatService;
        this.requestTranslator = requestTranslator;
        this.requestContextResolver = requestContextResolver;
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
        UserContext user = requestContextResolver.resolveChatUser();
        TraceContext traceContext = requestContextResolver.resolveTraceContext();
        RuntimeForwardHeaders forwardHeaders = requestContextResolver.forwardHeaders(cookieHeader);
        return chatService.startRun(user, traceContext, requestTranslator.toCommand(request), forwardHeaders)
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
        UserContext user = requestContextResolver.resolveChatUser();
        TraceContext traceContext = requestContextResolver.resolveTraceContext();
        RuntimeForwardHeaders forwardHeaders = requestContextResolver.forwardHeaders(cookieHeader);
        return chatService.stopRun(user, traceContext, runId, forwardHeaders)
                .map(this::toStopDto)
                .subscribeOn(Schedulers.boundedElastic());
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
                result.feedbackTargetMessageId()
        );
    }

}
