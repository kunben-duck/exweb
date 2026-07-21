package com.huawei.it.ex.one.share.interfaces.http;

import com.huawei.it.ex.one.share.application.config.ChatShareDeliveryProperties;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.security.application.context.AuthContextProvider;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.share.application.service.ChatShareDeliveryService;
import com.huawei.it.ex.one.share.application.service.ChatShareService;
import com.huawei.it.ex.one.share.application.service.CreateChatShareAndDeliveryCommand;
import com.huawei.it.ex.one.share.application.service.CreateChatShareCommand;
import com.huawei.it.ex.one.share.application.service.CreateChatShareDeliveryCommand;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.share.domain.ChatShare;
import com.huawei.it.ex.one.share.domain.ChatShareDelivery;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareAndDeliveryDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareDetailDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareDeliveryDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatSharePageDto;
import com.huawei.it.ex.one.share.interfaces.dto.CreateChatShareAndDeliveryRequest;
import com.huawei.it.ex.one.share.interfaces.dto.CreateChatShareDeliveryRequest;
import com.huawei.it.ex.one.share.interfaces.dto.CreateChatShareRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 单轮问答分享接口。
 *
 * <p>Controller 只在请求入口解析用户身份；分享 ACL 由 application 层通过
 * ChatShareAccessPolicy 防腐层判断，便于后续替换为企业权限框架。</p>
 */
@RestController
@RequestMapping("/v1/chat")
public class ChatShareController {
    private final ChatShareService shareService;
    private final ChatShareDeliveryService deliveryService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final ChatShareDeliveryProperties shareDeliveryProperties;
    private final ChatShareViewAssembler viewAssembler;

    public ChatShareController(ChatShareService shareService,
                               ChatShareDeliveryService deliveryService,
                               AuthContextProvider auth,
                               PermissionChecker permissionChecker,
                               ChatShareDeliveryProperties shareDeliveryProperties,
                               ChatShareViewAssembler viewAssembler) {
        this.shareService = shareService;
        this.deliveryService = deliveryService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.shareDeliveryProperties = shareDeliveryProperties;
        this.viewAssembler = viewAssembler;
    }

    /**
     * 为指定 assistant 消息创建固定快照分享。
     *
     * @param messageId 被分享的 assistant 消息 ID。
     * @param request 分享标题与过期时间。
     * @return 分享元数据。
     */
    @PostMapping("/messages/{messageId}/share")
    public Mono<ChatShareDto> create(@PathVariable("messageId") String messageId,
                                     @RequestBody(required = false) CreateChatShareRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    CreateChatShareCommand command = new CreateChatShareCommand(
                            messageId,
                            request == null ? null : request.title(),
                            request == null ? null : request.expiresAt()
                    );
                    return viewAssembler.toDto(shareService.create(user, command));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 创建分享快照并立即发送到指定 provider。
     *
     * @param messageId 被分享的 assistant 消息 ID。
     * @param request 分享创建与发送请求。
     * @return 分享快照元数据和发送结果。
     */
    @PostMapping("/messages/{messageId}/share/deliveries")
    public Mono<ChatShareAndDeliveryDto> createAndDeliver(
            @PathVariable("messageId") String messageId,
            @RequestBody(required = false) CreateChatShareAndDeliveryRequest request,
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader) {
        UserContext user = resolveChatUser();
        RuntimeForwardHeaders forwardHeaders = shareDeliveryForwardHeaders(cookieHeader);
        return Mono.fromCallable(() -> {
                    CreateChatShareAndDeliveryCommand command =
                            toCreateAndDeliveryCommand(messageId, request, forwardHeaders);
                    ChatShare share = shareService.create(user,
                            new CreateChatShareCommand(command.messageId(), command.title(), command.expiresAt()));
                    ChatShareDelivery delivery = deliveryService.deliver(user, new CreateChatShareDeliveryCommand(
                            share.id(),
                            command.provider(),
                            command.targetAccounts(),
                            command.groupIds(),
                            command.title(),
                            command.content(),
                            command.language(),
                            command.forwardHeaders()
                    ));
                    return new ChatShareAndDeliveryDto(
                            viewAssembler.toDto(share), viewAssembler.toDeliveryDto(delivery));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 登录后查看分享详情。
     *
     * @param shareId 分享 ID。
     * @return 固定分享快照。
     */
    @GetMapping("/shares/{shareId}")
    public Mono<ChatShareDetailDto> get(@PathVariable("shareId") String shareId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDetailDto(shareService.get(user, shareId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 把已有分享发送到指定 provider。
     *
     * @param shareId 分享 ID。
     * @param request 发送请求。
     * @return 分享发送结果。
     */
    @PostMapping("/shares/{shareId}/deliveries")
    public Mono<ChatShareDeliveryDto> deliver(@PathVariable("shareId") String shareId,
                                              @RequestBody(required = false) CreateChatShareDeliveryRequest request,
                                              @RequestHeader(value = HttpHeaders.COOKIE, required = false)
                                              String cookieHeader) {
        UserContext user = resolveChatUser();
        RuntimeForwardHeaders forwardHeaders = shareDeliveryForwardHeaders(cookieHeader);
        return Mono.fromCallable(() -> viewAssembler.toDeliveryDto(deliveryService.deliver(user,
                        toDeliveryCommand(shareId, request, forwardHeaders))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 撤销当前用户有权管理的分享。
     *
     * @param shareId 分享 ID。
     * @return 撤销后的分享元数据。
     */
    @DeleteMapping("/shares/{shareId}")
    public Mono<ChatShareDto> revoke(@PathVariable("shareId") String shareId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(shareService.revoke(user, shareId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 分页查询当前用户创建的分享。
     *
     * @param curPage 当前页码，从 1 开始。
     * @param pageSize 每页条数，应用层会限制最大值。
     * @return 当前用户分享列表。
     */
    @GetMapping("/shares")
    public Mono<ChatSharePageDto> list(@RequestParam(value = "curPage", defaultValue = "1") int curPage,
                                       @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toPageDto(shareService.listOwned(user, curPage, pageSize)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private RuntimeForwardHeaders shareDeliveryForwardHeaders(String cookieHeader) {
        /*
         * Cookie 只能在 Controller 入口捕获一次。后续 provider 调用运行在 boundedElastic，
         * 不能再读取 Servlet request 或企业 ThreadLocal，以免异步线程拿到错误上下文。
         */
        return RuntimeForwardHeaders.fromCookieHeader(
                cookieHeader,
                shareDeliveryProperties.normalizedForwardCookieMaxLength()
        );
    }

    private CreateChatShareAndDeliveryCommand toCreateAndDeliveryCommand(
            String messageId, CreateChatShareAndDeliveryRequest request, RuntimeForwardHeaders forwardHeaders) {
        return new CreateChatShareAndDeliveryCommand(
                messageId,
                request == null ? null : request.title(),
                request == null ? null : request.expiresAt(),
                request == null ? null : request.provider(),
                request == null ? null : request.targetAccounts(),
                request == null ? null : request.groupIds(),
                request == null ? null : request.content(),
                request == null ? null : request.language(),
                forwardHeaders
        );
    }

    private CreateChatShareDeliveryCommand toDeliveryCommand(
            String shareId, CreateChatShareDeliveryRequest request, RuntimeForwardHeaders forwardHeaders) {
        return new CreateChatShareDeliveryCommand(
                shareId,
                request == null ? null : request.provider(),
                request == null ? null : request.targetAccounts(),
                request == null ? null : request.groupIds(),
                request == null ? null : request.title(),
                request == null ? null : request.content(),
                request == null ? null : request.language(),
                forwardHeaders
        );
    }

}
