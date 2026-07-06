package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.service.chat.ChatHitlResponseCommand;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatHitlResponseStartResult;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatHitlResponseDto;
import com.huawei.finance.front.one.interfaces.chat.dto.SubmitChatHitlResponseRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 等待用户输入的澄清/审批续接接口。
 *
 * <p>Controller 只负责入口身份解析和请求快照转换；不同 Runtime 的续接协议由
 * 应用层 Runtime 防腐层适配，避免把 Relay 私有字段泄漏到 HTTP 入口。</p>
 */
@RestController
@RequestMapping("/api/v1/ex/chat/hitl")
public class ChatHitlController {
    private final FinanceChatFacade chatFacade;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final RuntimeForwardHeaderExtractor forwardHeaderExtractor;

    public ChatHitlController(FinanceChatFacade chatFacade, AuthContextProvider auth,
                              PermissionChecker permissionChecker,
                              RuntimeForwardHeaderExtractor forwardHeaderExtractor) {
        this.chatFacade = chatFacade;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.forwardHeaderExtractor = forwardHeaderExtractor;
    }

    /**
     * 提交等待态澄清/审批响应，并返回续接 run 的订阅信息。
     *
     * @param hitlRequestId 服务端下发的 HITL 请求 ID。
     * @param request 用户响应内容。
     * @param cookieHeader 原始 Cookie 头，仅作为可信下游请求头快照透传。
     * @return 续接 run 的创建结果。
     */
    @PostMapping("/{hitlRequestId}/responses")
    public Mono<ChatHitlResponseDto> submitResponse(
            @PathVariable("hitlRequestId") String hitlRequestId,
            @Valid @RequestBody SubmitChatHitlResponseRequest request,
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader) {
        if (request == null || request.approved() == null) {
            return Mono.error(new IllegalArgumentException("approved 不能为空"));
        }
        UserContext user = resolveChatUser();
        RuntimeForwardHeaders forwardHeaders = forwardHeaderExtractor.fromCookieHeader(cookieHeader);
        ChatHitlResponseCommand command = new ChatHitlResponseCommand(
                user,
                hitlRequestId,
                request.approved(),
                request.scope(),
                request.safeQuestionnaireAnswers(),
                request.safeMetadata()
        );
        return chatFacade.submitHitlResponse(command, forwardHeaders)
                .map(this::toDto);
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private ChatHitlResponseDto toDto(ChatHitlResponseStartResult result) {
        return new ChatHitlResponseDto(
                result.hitlRequestId(),
                result.continueRunId(),
                result.sessionId(),
                result.assistantMessageId(),
                result.streamTopicId(),
                result.firstSeq(),
                result.status()
        );
    }
}
