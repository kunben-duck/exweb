package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.model.ChatShareSourceAttachment;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourceMessage;
import com.huawei.it.ex.one.chat.application.model.ChatShareSourceSession;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;

/** Read-only application boundary used to build a share snapshot from chat facts. */
public interface ChatShareSourceService {
    ChatShareSourceMessage loadOwnedMessage(UserContext user, String messageId);

    ChatShareSourceSession loadOwnedSession(UserContext user, String sessionId);

    List<ChatShareSourceAttachment> findAttachments(UserContext user, String messageId);
}
