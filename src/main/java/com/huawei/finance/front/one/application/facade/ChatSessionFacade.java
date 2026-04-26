package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.util.List;

public interface ChatSessionFacade {
    ChatSession createSession(String tenantId, String userId, String title, String channel);
    ChatSession getSession(String tenantId, String userId, String sessionId);
    List<ChatSession> listSessions(String tenantId, String userId);
    ChatSession closeSession(String tenantId, String userId, String sessionId);
}
