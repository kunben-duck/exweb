package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.application.facade.ChatSessionFacade;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.service.ChatFeedbackApplicationService;
import com.huawei.finance.front.one.application.service.ChatRunApplicationService;
import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageFeedback;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import com.huawei.finance.front.one.domain.chat.ChatStreamStatus;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatSessionRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessagePageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatSessionDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatSessionPageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatSessionStateDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatStreamStatusDto;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatBranchRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.MessageFeedbackDto;
import com.huawei.finance.front.one.interfaces.chat.dto.SelectChatPathRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.UpdateChatSessionRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天会话管理接口。
 *
 * <p>第一版提供创建、查询、列表和关闭能力；会话归属由 application 层通过当前 UserContext 校验。</p>
 */
@RestController
@RequestMapping("/api/v1/ex/chat/sessions")
public class ChatSessionController {
    private final ChatSessionFacade facade;
    private final ChatRunApplicationService chatRunService;
    private final ChatFeedbackApplicationService feedbackService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;

    public ChatSessionController(ChatSessionFacade facade, ChatRunApplicationService chatRunService,
                                 ChatFeedbackApplicationService feedbackService, AuthContextProvider auth,
                                 PermissionChecker permissionChecker) {
        this.facade = facade;
        this.chatRunService = chatRunService;
        this.feedbackService = feedbackService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
    }

    /**
     * 创建当前用户的新聊天会话。
     *
     * @param request 会话创建请求；为空时使用默认标题和 web 渠道。
     * @return 新建会话元数据。
     */
    @PostMapping
    public Mono<ChatSessionDto> create(@RequestBody(required = false) CreateChatSessionRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    String title = request == null ? null : request.title();
                    String channel = request == null ? null : request.channel();
                    return toDto(facade.createSession(user, title, channel));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 分页查询当前用户会话列表。
     *
     * @param cursor 上一页返回的游标；为空时查询第一页。
     * @param limit 最大返回条数，应用层会做上限保护。
     * @return 会话分页结果，按最近更新时间倒序排列。
     */
    @GetMapping
    public Mono<ChatSessionPageDto> list(@RequestParam(value = "cursor", required = false) String cursor,
                                              @RequestParam(value = "limit", defaultValue = "20") int limit) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatSessionPage page = facade.listSessions(user, cursor, limit);
                    return new ChatSessionPageDto(page.items().stream().map(this::toDto).toList(), page.nextCursor());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询当前用户可见的单个会话。
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @return 会话元数据。
     */
    @GetMapping("/{sessionId}")
    public Mono<ChatSessionDto> get(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toDto(facade.getSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询会话页面初始化状态。
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @param messageLimit 返回最近历史消息条数，适合页面首次渲染。
     * @return 会话元数据、最近历史消息分页和当前流式状态。
     */
    @GetMapping("/{sessionId}/state")
    public Mono<ChatSessionStateDto> state(@PathVariable("sessionId") String sessionId,
                                                @RequestParam(value = "messageLimit", defaultValue = "50") int messageLimit) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatSession session = facade.getSession(user, sessionId);
                    ChatMessagePage messages = facade.listMessages(user, sessionId, null, messageLimit);
                    ChatStreamStatus streamStatus = chatRunService.streamStatus(user, sessionId);
                    return new ChatSessionStateDto(
                            toDto(session),
                            toMessagePageDto(user, sessionId, messages),
                            toStreamStatusDto(streamStatus)
                    );
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询所选会话的历史消息。
     *
     * <p>返回值按创建时间正序排列，适合前端切换会话后直接渲染历史气泡。
     * 正在输出中的增量内容仍通过 WebSocket 或 Event Resume。</p>
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @param cursor 上一页返回的游标；为空时查询最近一页。
     * @param limit 最大返回条数，应用层会做上限保护。
     * @return 历史消息分页结果。
     */
    @GetMapping("/{sessionId}/messages")
    public Mono<ChatMessagePageDto> messages(@PathVariable("sessionId") String sessionId,
                                                  @RequestParam(value = "leafMessageId", required = false) String leafMessageId,
                                                  @RequestParam(value = "cursor", required = false) String cursor,
                                                  @RequestParam(value = "limit", defaultValue = "50") int limit) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toMessagePageDto(user, sessionId,
                        facade.listMessages(user, sessionId, leafMessageId, cursor, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询某条消息在同一父节点下的候选版本。
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @param messageId 消息标识；服务端会校验消息属于该会话。
     * @return 同父节点、同角色的候选消息列表，按 siblingIndex 排列。
     */
    @GetMapping("/{sessionId}/messages/{messageId}/variants")
    public Mono<List<ChatMessageDto>> variants(@PathVariable("sessionId") String sessionId,
                                               @PathVariable("messageId") String messageId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toMessageDtos(user, sessionId, facade.listVariants(user, sessionId, messageId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 切换会话当前 active path。
     *
     * <p>该接口只改变会话当前叶子，不会创建新 run，也不会触发 Runtime/SubAgent 调用。</p>
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @param request 目标叶子消息请求。
     * @return 切换后的会话元数据。
     */
    @PostMapping("/{sessionId}/path")
    public Mono<ChatSessionDto> selectPath(@PathVariable("sessionId") String sessionId,
                                           @RequestBody SelectChatPathRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toDto(facade.selectPath(user, sessionId,
                        request == null ? null : request.leafMessageId())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 从会话中的某条消息创建只读历史快照分支。
     *
     * <p>服务端会复制 root 到来源消息的可见路径，并把复制出的历史消息标记为 locked。
     * 新分支后续新增消息仍然是普通 NORMAL 消息，可以继续编辑或重新生成。</p>
     *
     * @param sessionId 来源会话标识。
     * @param request 分支来源消息与可选标题。
     * @return 新建分支会话元数据。
     */
    @PostMapping("/{sessionId}/branches")
    public Mono<ChatSessionDto> createBranch(@PathVariable("sessionId") String sessionId,
                                             @RequestBody CreateChatBranchRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toDto(facade.createBranch(user, sessionId,
                        request == null ? null : request.sourceMessageId(),
                        request == null ? null : request.title())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 重命名当前用户会话。
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @param request 更新请求；title 为空时保留原标题。
     * @return 更新后的会话元数据。
     */
    @PatchMapping("/{sessionId}")
    public Mono<ChatSessionDto> update(@PathVariable("sessionId") String sessionId,
                                            @RequestBody(required = false) UpdateChatSessionRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toDto(facade.renameSession(user, sessionId, request == null ? null : request.title())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 归档当前用户会话。
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @return 归档后的会话元数据。
     */
    @PostMapping("/{sessionId}/archive")
    public Mono<ChatSessionDto> archive(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toDto(facade.archiveSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 恢复当前用户归档会话。
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @return 恢复后的会话元数据。
     */
    @PostMapping("/{sessionId}/restore")
    public Mono<ChatSessionDto> restore(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toDto(facade.restoreSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 关闭当前用户会话。
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @return 关闭后的会话元数据。
     */
    @PostMapping("/{sessionId}/close")
    public Mono<ChatSessionDto> close(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toDto(facade.closeSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private ChatSessionDto toDto(ChatSession session) {
        return new ChatSessionDto(
                session.id(),
                session.tenantId(),
                session.userId(),
                session.title(),
                session.status(),
                session.channel(),
                session.currentLeafMessageId(),
                session.rootSessionId(),
                session.branchSourceSessionId(),
                session.branchSourceMessageId(),
                session.createdAt(),
                session.updatedAt()
        );
    }

    private ChatMessageDto toMessageDto(ChatMessage message, ChatMessageFeedback feedback) {
        return new ChatMessageDto(
                message.id(),
                message.sessionId(),
                message.parentMessageId(),
                message.nodeOrder(),
                message.treeDepth(),
                message.siblingIndex(),
                message.role(),
                message.content(),
                message.tokenCount(),
                message.runId(),
                message.originType(),
                message.locked(),
                message.sourceSessionId(),
                message.sourceMessageId(),
                message.editedFromMessageId(),
                message.regeneratedFromMessageId(),
                feedback == null ? null : toFeedbackDto(feedback),
                message.createdAt()
        );
    }

    private ChatMessagePageDto toMessagePageDto(UserContext user, String sessionId, ChatMessagePage page) {
        return new ChatMessagePageDto(toMessageDtos(user, sessionId, page.items()), page.nextCursor());
    }

    private List<ChatMessageDto> toMessageDtos(UserContext user, String sessionId, List<ChatMessage> messages) {
        Map<String, ChatMessageFeedback> feedbacks = feedbackService.findActiveByMessages(user, sessionId, messages);
        return messages.stream()
                .map(message -> toMessageDto(message, feedbacks.get(message.id())))
                .toList();
    }

    private MessageFeedbackDto toFeedbackDto(ChatMessageFeedback feedback) {
        return new MessageFeedbackDto(
                feedback.id(),
                feedback.messageId(),
                feedback.runId(),
                feedback.rating(),
                feedback.status(),
                feedback.createdAt(),
                feedback.updatedAt()
        );
    }

    private ChatStreamStatusDto toStreamStatusDto(ChatStreamStatus status) {
        return new ChatStreamStatusDto(
                status.sessionId(),
                status.latestSeq(),
                status.readCursorSeq(),
                status.activeRunId(),
                status.activeRunStatus() == null ? null : status.activeRunStatus().name(),
                status.activeStreamTopicId(),
                status.activeRunFirstSeq(),
                status.activeRunLastSeq(),
                status.cancellable()
        );
    }
}
