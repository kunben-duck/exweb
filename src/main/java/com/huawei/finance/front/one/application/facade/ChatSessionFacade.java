package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.util.List;

public interface ChatSessionFacade {
    ChatSession createSession(String title, String channel);
    ChatSession getSession(String sessionId);
    List<ChatSession> listSessions();
    ChatSession closeSession(String sessionId);
}
