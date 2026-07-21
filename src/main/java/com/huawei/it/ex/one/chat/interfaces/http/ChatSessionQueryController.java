package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.application.service.ChatSessionService;
import com.huawei.it.ex.one.chat.domain.ChatSessionNumberPage;
import com.huawei.it.ex.one.chat.domain.ChatSessionPage;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatSessionNumberPageDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatSessionPageDto;
import com.huawei.it.ex.one.security.application.context.AuthContextProvider;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Cursor and number-page session list endpoints. */
@RestController
@RequestMapping("/v1/chat/sessions")
@Validated
public class ChatSessionQueryController {
    private final ChatSessionService sessionService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final ChatSessionViewAssembler viewAssembler;

    public ChatSessionQueryController(
            ChatSessionService sessionService,
            AuthContextProvider auth,
            PermissionChecker permissionChecker,
            ChatSessionViewAssembler viewAssembler) {
        this.sessionService = sessionService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.viewAssembler = viewAssembler;
    }

    @GetMapping
    public Mono<ChatSessionPageDto> list(
            @Size(max = 128, message = "appId 长度不能超过 128")
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatSessionPage page = sessionService.listSessions(user, appId, cursor, limit);
                    Map<String, String> firstAnswers = sessionService.findFirstAssistantAnswers(
                            user, page.items());
                    return new ChatSessionPageDto(
                            page.items().stream()
                                    .map(session -> viewAssembler.toDto(
                                            session, firstAnswers.get(session.id())))
                                    .toList(),
                            page.nextCursor());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/page")
    public Mono<ChatSessionNumberPageDto> listByPage(
            @Size(max = 128, message = "appId 长度不能超过 128")
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "curPage", defaultValue = "1") int curPage,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        UserContext user = resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatSessionNumberPage page = sessionService.listSessionsByPage(
                            user, appId, curPage, pageSize);
                    Map<String, String> firstAnswers = sessionService.findFirstAssistantAnswers(
                            user, page.items());
                    return new ChatSessionNumberPageDto(
                            page.items().stream()
                                    .map(session -> viewAssembler.toDto(
                                            session, firstAnswers.get(session.id())))
                                    .toList(),
                            page.curPage(),
                            page.pageSize(),
                            page.totalRows(),
                            page.totalPages());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }
}
