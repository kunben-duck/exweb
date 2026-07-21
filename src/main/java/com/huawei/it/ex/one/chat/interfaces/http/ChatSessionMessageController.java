package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.application.service.ChatSessionService;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessagePage;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessageDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessagePageDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessageTreeDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatSessionDto;
import com.huawei.it.ex.one.chat.interfaces.dto.CreateChatBranchRequest;
import com.huawei.it.ex.one.chat.interfaces.dto.SelectChatPathRequest;
import com.huawei.it.ex.one.security.application.context.AuthContextProvider;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Message history, tree, version selection, and branch endpoints. */
@RestController
@RequestMapping("/v1/chat/sessions")
@Validated
public class ChatSessionMessageController {
    private final ChatSessionService sessionService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final ChatSessionViewAssembler viewAssembler;

    public ChatSessionMessageController(
            ChatSessionService sessionService,
            AuthContextProvider auth,
            PermissionChecker permissionChecker,
            ChatSessionViewAssembler viewAssembler) {
        this.sessionService = sessionService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.viewAssembler = viewAssembler;
    }

    @GetMapping("/{sessionId}/messages")
    public Mono<ChatMessagePageDto> messages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "leafMessageId", required = false) String leafMessageId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatMessagePage page = sessionService.listMessages(
                            user, sessionId, leafMessageId, cursor, limit);
                    List<ChatMessage> treeNodes = page.items().isEmpty()
                            ? List.of()
                            : sessionService.listMessageTreeNodes(user, sessionId);
                    return viewAssembler.toMessagePageDto(user, sessionId, page, treeNodes);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{sessionId}/messages/tree")
    public Mono<ChatMessageTreeDto> messageTree(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatSession session = sessionService.getSession(user, sessionId);
                    List<ChatMessage> messages = sessionService.listMessageTree(user, sessionId);
                    return viewAssembler.toMessageTreeDto(user, session, messages);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{sessionId}/messages/{messageId}/variants")
    public Mono<List<ChatMessageDto>> variants(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("messageId") String messageId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toMessageDtos(
                        user,
                        sessionId,
                        sessionService.listVariants(user, sessionId, messageId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{sessionId}/path")
    public Mono<ChatSessionDto> selectPath(
            @PathVariable("sessionId") String sessionId,
            @RequestBody SelectChatPathRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(sessionService.selectPath(
                        user,
                        sessionId,
                        request == null ? null : request.leafMessageId())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{sessionId}/branches")
    public Mono<ChatSessionDto> createBranch(
            @PathVariable("sessionId") String sessionId,
            @RequestBody CreateChatBranchRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(sessionService.createBranch(
                        user,
                        sessionId,
                        request == null ? null : request.sourceMessageId(),
                        request == null ? null : request.title())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }
}
