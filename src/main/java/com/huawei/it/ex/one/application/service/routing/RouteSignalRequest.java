package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import java.util.List;

/**
 * 路由阶段上下文。
 *
 * <p>runId 只用于把 intent-agent 路由过程转换成可落库的 ChatEvent；没有 runId 的同步调用仍只返回最终路由结果。
 * intentQuery 是只提供给 IntentAgent 的临时问题，不覆盖 command 中的历史消息和 Runtime query。</p>
 */
public record RouteSignalRequest(String runId,
                                 UserContext user,
                                 ChatSession session,
                                 ChatCommand command,
                                 List<AttachmentRef> attachments,
                                 MemoryContext memory,
                                 String intentQuery) {

    public RouteSignalRequest(String runId,
                              UserContext user,
                              ChatSession session,
                              ChatCommand command,
                              List<AttachmentRef> attachments,
                              MemoryContext memory) {
        this(runId, user, session, command, attachments, memory,
                command == null ? null : command.message());
    }
}
