package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.application.service.share.ChatShareApplicationService;
import com.huawei.finance.front.one.application.service.share.CreateChatShareCommand;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatShare;
import com.huawei.finance.front.one.domain.chat.ChatShareAttachmentSnapshot;
import com.huawei.finance.front.one.domain.chat.ChatShareMessageSnapshot;
import com.huawei.finance.front.one.domain.chat.ChatSharePage;
import com.huawei.finance.front.one.domain.chat.ChatShareSnapshotPart;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessagePartDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatShareAttachmentSnapshotDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatShareDetailDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatShareDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatSharePageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatShareSnapshotMessageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatShareRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@RequestMapping("/api/v1/ex/chat")
public class ChatShareController {
    private final ChatShareApplicationService shareService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;

    public ChatShareController(ChatShareApplicationService shareService, AuthContextProvider auth,
                               PermissionChecker permissionChecker) {
        this.shareService = shareService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
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
                    return toDto(shareService.create(user, command));
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
        return Mono.fromCallable(() -> toDetailDto(shareService.get(user, shareId)))
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
        return Mono.fromCallable(() -> toDto(shareService.revoke(user, shareId)))
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
        return Mono.fromCallable(() -> toPageDto(shareService.listOwned(user, curPage, pageSize)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private ChatSharePageDto toPageDto(ChatSharePage page) {
        return new ChatSharePageDto(
                page.items().stream().map(this::toDto).toList(),
                page.curPage(),
                page.pageSize(),
                page.totalRows(),
                page.totalPages()
        );
    }

    private ChatShareDetailDto toDetailDto(ChatShare share) {
        return new ChatShareDetailDto(
                toDto(share),
                toMessageDto(share.snapshot().question()),
                toMessageDto(share.snapshot().answer()),
                toPartDtos(share.snapshot().parts())
        );
    }

    private ChatShareDto toDto(ChatShare share) {
        return new ChatShareDto(
                share.id(),
                share.title(),
                share.scope(),
                share.visibility(),
                share.status(),
                share.expiresAt(),
                share.sourceSessionId(),
                share.sourceUserMessageId(),
                share.sourceAssistantMessageId(),
                share.sourceRunId(),
                share.createdAt(),
                share.updatedAt()
        );
    }

    private ChatShareSnapshotMessageDto toMessageDto(ChatShareMessageSnapshot message) {
        return new ChatShareSnapshotMessageDto(
                message.messageId(),
                message.sessionId(),
                message.role(),
                message.content(),
                message.runId(),
                message.metadataJson(),
                toAttachmentDtos(message.attachments()),
                message.createdAt()
        );
    }

    private List<ChatShareAttachmentSnapshotDto> toAttachmentDtos(List<ChatShareAttachmentSnapshot> attachments) {
        return attachments == null ? List.of() : attachments.stream()
                .map(attachment -> new ChatShareAttachmentSnapshotDto(
                        attachment.documentId(),
                        attachment.name(),
                        attachment.contentType(),
                        attachment.sizeBytes()
                ))
                .toList();
    }

    private List<ChatMessagePartDto> toPartDtos(List<ChatShareSnapshotPart> parts) {
        return parts == null ? List.of() : parts.stream()
                .map(part -> new ChatMessagePartDto(
                        part.partId(),
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
}
