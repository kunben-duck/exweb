package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatStreamStatus;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.Collection;
import java.util.Map;

/** Read-only ChatRun boundary used by interface assemblers. */
public interface ChatRunQueryService {
    Map<String, ChatRun> findOwnedRunsByIds(UserContext user, Collection<String> runIds);

    ChatStreamStatus streamStatus(UserContext user, String sessionId);
}
