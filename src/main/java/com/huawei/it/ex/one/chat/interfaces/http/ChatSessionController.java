package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.application.service.ChatSessionService;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.interfaces.dto.BatchDeleteChatSessionsDto;
import com.huawei.it.ex.one.chat.interfaces.dto.BatchDeleteChatSessionsRequest;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatSessionDto;
import com.huawei.it.ex.one.chat.interfaces.dto.CreateChatSessionRequest;
import com.huawei.it.ex.one.chat.interfaces.dto.MarkChatSessionReadRequest;
import com.huawei.it.ex.one.chat.interfaces.dto.UpdateChatSessionRequest;
import com.huawei.it.ex.one.security.application.context.AuthContextProvider;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Session lifecycle endpoints. Message and list queries live in focused sibling controllers. */
@RestController
@RequestMapping("/v1/chat/sessions")
@Validated
public class ChatSessionController {
    private final ChatSessionService sessionService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final ChatSessionViewAssembler viewAssembler;

    public ChatSessionController(
            ChatSessionService sessionService,
            AuthContextProvider auth,
            PermissionChecker permissionChecker,
            ChatSessionViewAssembler viewAssembler) {
        this.sessionService = sessionService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.viewAssembler = viewAssembler;
    }

    @PostMapping
    public Mono<ChatSessionDto> create(
            @Valid @RequestBody(required = false) CreateChatSessionRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    String title = request == null ? null : request.title();
                    String channel = request == null ? null : request.channel();
                    String appId = request == null ? null : request.appId();
                    String appName = request == null ? null : request.appName();
                    return viewAssembler.toDto(
                            sessionService.createSession(user, title, channel, appId, appName));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{sessionId}")
    public Mono<ChatSessionDto> get(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(sessionService.getSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{sessionId}/read")
    public Mono<ChatSessionDto> markRead(
            @PathVariable("sessionId") String sessionId,
            @Valid @RequestBody MarkChatSessionReadRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(
                        sessionService.markSessionRead(user, sessionId, request.readThroughSeq())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/{sessionId}")
    public Mono<ChatSessionDto> update(
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) UpdateChatSessionRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(sessionService.renameSession(
                        user, sessionId, request == null ? null : request.title())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{sessionId}/archive")
    public Mono<ChatSessionDto> archive(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(
                        sessionService.archiveSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{sessionId}/restore")
    public Mono<ChatSessionDto> restore(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(
                        sessionService.restoreSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{sessionId}")
    public Mono<ChatSessionDto> delete(@PathVariable("sessionId") String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(
                        sessionService.deleteSession(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping
    public Mono<BatchDeleteChatSessionsDto> deleteBatch(
            @RequestBody(required = false) BatchDeleteChatSessionsRequest request) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    List<ChatSession> deleted = sessionService.deleteSessions(
                            user, request == null ? null : request.sessionIds());
                    List<ChatSessionDto> items = deleted.stream().map(viewAssembler::toDto).toList();
                    return new BatchDeleteChatSessionsDto(items.size(), items);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }
}
