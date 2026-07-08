package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.application.facade.ChatSessionFacade;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.service.chat.ChatFeedbackApplicationService;
import com.huawei.finance.front.one.application.service.chat.ChatRunApplicationService;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageAttachment;
import com.huawei.finance.front.one.domain.chat.ChatMessageFeedback;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionNumberPage;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import com.huawei.finance.front.one.interfaces.chat.dto.BatchDeleteChatSessionsDto;
import com.huawei.finance.front.one.interfaces.chat.dto.BatchDeleteChatSessionsRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageAttachmentDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessagePageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessagePartDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageTreeDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageTreeNodeDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageVersionInfoDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatSessionDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatSessionNumberPageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatSessionPageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatBranchRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatSessionRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.MessageFeedbackDto;
import com.huawei.finance.front.one.interfaces.chat.dto.SelectChatPathRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.UpdateChatSessionRequest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天会话管理接口。
 *
 * <p>第一版提供创建、查询、列表、归档、恢复和删除能力；会话归属由 application 层通过当前 UserContext 校验。</p>
 */
@RestController
@RequestMapping("/api/v1/ex/chat/sessions")
public class ChatSessionController {
    private static final Logger log = LoggerFactory.getLogger(ChatSessionController.class);
    private static final String ASSISTANT_ROLE = "assistant";

    private final ChatSessionFacade facade;
    private final ChatFeedbackApplicationService feedbackService;
    private final ChatRunApplicationService chatRunService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final ChatMessageVersionViewAssembler versionViewAssembler;

    public ChatSessionController(ChatSessionFacade facade, ChatFeedbackApplicationService feedbackService,
                                 ChatRunApplicationService chatRunService,
                                 AuthContextProvider auth,
                                 PermissionChecker permissionChecker,
                                 ChatMessageVersionViewAssembler versionViewAssembler) {
        this.facade = facade;
        this.feedbackService = feedbackService;
        this.chatRunService = chatRunService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.versionViewAssembler = versionViewAssembler;
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
                    Map<String, String> firstAnswers = facade.findFirstAssistantAnswers(user, page.items());
                    return new ChatSessionPageDto(
                            page.items().stream()
                                    .map(session -> toDto(session, firstAnswers.get(session.id())))
                                    .toList(),
                            page.nextCursor()
                    );
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 按页码分页查询当前用户历史会话列表。
     *
     * <p>该接口用于需要 {@code curPage/pageSize/totalRows} 的传统分页 UI；现有 cursor 分页接口
     * 保持不变。返回项会批量装配首条 assistant 完整回答，避免列表页逐会话查询历史消息。</p>
     *
     * @param curPage 当前页码，从 1 开始；非法值由应用层归一化。
     * @param pageSize 每页条数；应用层会限制最大值。
     * @return 会话页码分页结果。
     */
    @GetMapping("/page")
    public Mono<ChatSessionNumberPageDto> listByPage(
            @RequestParam(value = "curPage", defaultValue = "1") int curPage,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatSessionNumberPage page = facade.listSessionsByPage(user, curPage, pageSize);
                    Map<String, String> firstAnswers = facade.findFirstAssistantAnswers(user, page.items());
                    return new ChatSessionNumberPageDto(
                            page.items().stream()
                                    .map(session -> toDto(session, firstAnswers.get(session.id())))
                                    .toList(),
                            page.curPage(),
                            page.pageSize(),
                            page.totalRows(),
                            page.totalPages()
                    );
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
        return Mono.fromCallable(() -> {
                    ChatMessagePage page = facade.listMessages(user, sessionId, leafMessageId, cursor, limit);
                    Map<String, ChatMessageVersionInfoDto> versionInfos = page.items().isEmpty()
                            ? Map.of()
                            : versionViewAssembler.assemble(page.items(), facade.listMessageTreeNodes(user, sessionId));
                    return toMessagePageDto(user, sessionId, page, versionInfos);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询当前会话完整可见消息树。
     *
     * <p>该接口返回 mapping/current leaf 结构，但只返回 user/assistant 业务可见消息；
     * hidden system 和下游工具原始节点不会暴露给前端。</p>
     *
     * @param sessionId 会话标识；服务端会校验会话归属且排除已删除会话。
     * @return 当前会话完整消息树。
     */
    @GetMapping("/{sessionId}/messages/tree")
    public Mono<ChatMessageTreeDto> messageTree(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatSession session = facade.getSession(user, sessionId);
                    List<ChatMessage> messages = facade.listMessageTree(user, sessionId);
                    return toMessageTreeDto(user, session, messages);
                })
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
     * <p>该接口只改变会话当前叶子，不会创建新 run，也不会触发 Runtime/DomainAgent 调用。</p>
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
     * 软删除当前用户会话。
     *
     * <p>该接口只把会话状态置为 DELETED，不物理删除消息、run、event、反馈或附件引用。
     * 若会话仍有 active run，应用层会先主动取消本轮 run，再删除会话。</p>
     *
     * @param sessionId 会话标识；服务端会校验会话归属。
     * @return 删除后的会话元数据。
     */
    @DeleteMapping("/{sessionId}")
    public Mono<ChatSessionDto> delete(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> toDto(facade.deleteSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 批量软删除当前用户会话。
     *
     * <p>删除采用 all-or-nothing 语义：只要任意会话不存在或不属于当前用户，
     * 本次批量请求整体失败，不做部分删除；运行中的会话会由应用层先主动取消 run。</p>
     *
     * @param request 批量删除请求，包含待删除 sessionIds。
     * @return 删除后的会话快照列表。
     */
    @DeleteMapping
    public Mono<BatchDeleteChatSessionsDto> deleteBatch(@RequestBody(required = false) BatchDeleteChatSessionsRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    List<ChatSession> deleted = facade.deleteSessions(user, request == null ? null : request.sessionIds());
                    List<ChatSessionDto> items = deleted.stream().map(this::toDto).toList();
                    return new BatchDeleteChatSessionsDto(items.size(), items);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private ChatSessionDto toDto(ChatSession session) {
        return toDto(session, null);
    }

    private ChatSessionDto toDto(ChatSession session, String firstAssistantAnswer) {
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
                firstAssistantAnswer,
                session.createdAt(),
                session.updatedAt()
        );
    }

    private ChatMessageDto toMessageDto(ChatMessage message, ChatMessageFeedback feedback,
                                        String assistantSource,
                                        ChatMessageVersionInfoDto versionInfo) {
        String resolvedAssistantSource = ASSISTANT_ROLE.equals(message.role()) ? assistantSource : null;
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
                resolvedAssistantSource,
                message.originType(),
                message.locked(),
                message.sourceSessionId(),
                message.sourceMessageId(),
                message.editedFromMessageId(),
                message.regeneratedFromMessageId(),
                toPartDtos(message.parts()),
                toAttachmentDtos(message.attachments()),
                feedback == null ? null : toFeedbackDto(feedback),
                versionInfo,
                message.createdAt()
        );
    }

    private List<ChatMessagePartDto> toPartDtos(List<ChatMessagePart> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        return parts.stream()
                .map(part -> new ChatMessagePartDto(
                        part.id(),
                        part.messageId(),
                        part.runId(),
                        part.partType(),
                        part.sourceType(),
                        part.contentText(),
                        part.title(),
                        part.status(),
                        part.channel(),
                        part.displayHint(),
                        part.visible(),
                        part.payload(),
                        part.partOrder(),
                        part.createdAt()
                ))
                .toList();
    }

    private List<ChatMessageAttachmentDto> toAttachmentDtos(List<ChatMessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
                .map(attachment -> new ChatMessageAttachmentDto(
                        attachment.id(),
                        attachment.documentId(),
                        attachment.attachmentOrder(),
                        attachment.name(),
                        attachment.contentType(),
                        attachment.sizeBytes(),
                        attachment.sourceAttachmentId(),
                        attachment.createdAt()
                ))
                .toList();
    }

    private ChatMessagePageDto toMessagePageDto(UserContext user, String sessionId, ChatMessagePage page,
                                                Map<String, ChatMessageVersionInfoDto> versionInfos) {
        return new ChatMessagePageDto(toMessageDtos(user, sessionId, page.items(), versionInfos), page.nextCursor());
    }

    private ChatMessageTreeDto toMessageTreeDto(UserContext user, ChatSession session, List<ChatMessage> messages) {
        List<ChatMessage> orderedMessages = messages == null ? List.of() : messages.stream()
                .sorted(Comparator.comparing(ChatMessage::nodeOrder).thenComparing(ChatMessage::createdAt))
                .toList();
        Set<String> messageIds = orderedMessages.stream().map(ChatMessage::id).collect(Collectors.toSet());
        Map<String, ChatMessageFeedback> feedbacks = feedbackService.findActiveByMessages(user, session.id(), orderedMessages);
        Map<String, ChatMessageVersionInfoDto> versionInfos =
                versionViewAssembler.assemble(orderedMessages, orderedMessages);
        Map<String, String> assistantSources = assistantSources(user, orderedMessages);
        Map<String, List<String>> childrenByParent = orderedMessages.stream()
                .filter(message -> message.parentMessageId() != null && messageIds.contains(message.parentMessageId()))
                .collect(Collectors.groupingBy(ChatMessage::parentMessageId, LinkedHashMap::new,
                        Collectors.mapping(ChatMessage::id, Collectors.toList())));
        Map<String, ChatMessageTreeNodeDto> mapping = new LinkedHashMap<>();
        for (ChatMessage message : orderedMessages) {
            mapping.put(message.id(), new ChatMessageTreeNodeDto(
                    message.id(),
                    toMessageDto(message, feedbacks.get(message.id()), assistantSources.get(message.runId()),
                            versionInfos.get(message.id())),
                    message.parentMessageId(),
                    childrenByParent.getOrDefault(message.id(), List.of())
            ));
        }
        List<String> rootMessageIds = orderedMessages.stream()
                .filter(message -> message.parentMessageId() == null || !messageIds.contains(message.parentMessageId()))
                .map(ChatMessage::id)
                .toList();
        return new ChatMessageTreeDto(session.id(), session.currentLeafMessageId(), rootMessageIds, mapping);
    }

    private List<ChatMessageDto> toMessageDtos(UserContext user, String sessionId, List<ChatMessage> messages) {
        return toMessageDtos(user, sessionId, messages, Map.of());
    }

    private List<ChatMessageDto> toMessageDtos(UserContext user, String sessionId, List<ChatMessage> messages,
                                               Map<String, ChatMessageVersionInfoDto> versionInfos) {
        Map<String, ChatMessageFeedback> feedbacks = feedbackService.findActiveByMessages(user, sessionId, messages);
        Map<String, String> assistantSources = assistantSources(user, messages);
        return messages.stream()
                .map(message -> toMessageDto(message, feedbacks.get(message.id()), assistantSources.get(message.runId()),
                        versionInfos.get(message.id())))
                .toList();
    }

    private Map<String, String> assistantSources(UserContext user, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Map.of();
        }
        List<String> runIds = messages.stream()
                .filter(message -> ASSISTANT_ROLE.equals(message.role()))
                .map(ChatMessage::runId)
                .filter(runId -> runId != null && !runId.isBlank())
                .distinct()
                .toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        try {
            return chatRunService.findOwnedRunsByIds(user, runIds).values().stream()
                    .filter(run -> run.runtimeProvider() != null && !run.runtimeProvider().isBlank())
                    .collect(Collectors.toMap(ChatRun::id, ChatRun::runtimeProvider, (left, right) -> left,
                            LinkedHashMap::new));
        } catch (RuntimeException ex) {
            log.warn("历史消息 assistantSource 查询失败，将按空来源返回。runCount={}, reason={}",
                    runIds.size(), ex.getMessage());
            return Map.of();
        }
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
                feedback.createdAt(),
                feedback.updatedAt()
        );
    }
}
