package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.application.facade.ChatSessionFacade;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatSessionRequest;
import com.huawei.finance.front.one.interfaces.chat.dto.FrontChatSessionDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 聊天会话管理接口。
 *
 * <p>第一版提供创建、查询、列表和关闭能力；会话归属由 application 层校验 tenantId + userId + sessionId。</p>
 */
@RestController
@RequestMapping("/api/v1/finance/chat/sessions")
public class ChatSessionController {
    private final ChatSessionFacade facade;

    public ChatSessionController(ChatSessionFacade facade) {
        this.facade = facade;
    }

    @PostMapping
    public FrontChatSessionDto create(@RequestBody(required = false) CreateChatSessionRequest request,
                                      @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                      @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        String title = request == null ? null : request.title();
        String channel = request == null ? null : request.channel();
        return toDto(facade.createSession(tenantId, userId, title, channel));
    }

    @GetMapping
    public List<FrontChatSessionDto> list(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                          @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return facade.listSessions(tenantId, userId).stream().map(this::toDto).toList();
    }

    @GetMapping("/{sessionId}")
    public FrontChatSessionDto get(@PathVariable String sessionId,
                                   @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                   @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return toDto(facade.getSession(tenantId, userId, sessionId));
    }

    @PostMapping("/{sessionId}/close")
    public FrontChatSessionDto close(@PathVariable String sessionId,
                                     @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                     @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return toDto(facade.closeSession(tenantId, userId, sessionId));
    }

    private FrontChatSessionDto toDto(ChatSession session) {
        return new FrontChatSessionDto(session.id(), session.tenantId(), session.userId(), session.title(), session.status(), session.channel(), session.createdAt(), session.updatedAt());
    }
}
