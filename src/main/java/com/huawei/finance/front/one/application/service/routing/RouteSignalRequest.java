package com.huawei.finance.front.one.application.service.routing;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;

/**
 * 路由阶段上下文。
 *
 * <p>runId 只用于把 intent-agent 路由过程转换成可落库的 ChatEvent；没有 runId 的同步调用仍只返回最终路由结果。</p>
 */
public record RouteSignalRequest(String runId,
                                 UserContext user,
                                 ChatSession session,
                                 ChatCommand command,
                                 List<AttachmentRef> attachments,
                                 MemoryContext memory) {
}
