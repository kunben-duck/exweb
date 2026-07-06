package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.chat.ChatMessagePartDraft;
import java.time.Instant;
import java.util.List;

/**
 * 构造 assistant message parts 的上下文。
 *
 * <p>parts 是历史消息的过程回显来源。ANSWER part 默认隐藏，正文仍以
 * {@code ChatMessage.content} 为准；runtime.* 事件转出的 part 不参与正文拼接。</p>
 */
record MessagePartBuildContext(
        String tenantId,
        String userId,
        String sessionId,
        String messageId,
        String runId,
        String content,
        List<ChatMessagePartDraft> drafts,
        Instant now,
        int startOrder
) {
    MessagePartBuildContext(String tenantId, String userId, String sessionId, String messageId, String runId,
                            String content, List<ChatMessagePartDraft> drafts, Instant now) {
        this(tenantId, userId, sessionId, messageId, runId, content, drafts, now, 1);
    }

    MessagePartBuildContext {
        startOrder = Math.max(1, startOrder);
    }
}
