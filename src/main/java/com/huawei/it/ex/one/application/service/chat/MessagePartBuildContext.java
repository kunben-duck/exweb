/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;

import java.time.Instant;
import java.util.List;

/**
 * 构造 assistant message parts 的上下文。
 *
 * <p>parts 是历史消息的过程回显来源。ANSWER part 默认隐藏，正文仍以
 * {@code ChatMessage.content} 为准；runtime.* 事件转出的 part 不参与正文拼接。等待路由切换确认时
 * 可以只保存控制 parts，不提前生成最终 ANSWER。</p>
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
        int startOrder,
        boolean appendAnswerPart
) {
    MessagePartBuildContext(String tenantId, String userId, String sessionId, String messageId, String runId,
                            String content, List<ChatMessagePartDraft> drafts, Instant now) {
        this(tenantId, userId, sessionId, messageId, runId, content, drafts, now, 1, true);
    }

    MessagePartBuildContext(String tenantId, String userId, String sessionId, String messageId, String runId,
                            String content, List<ChatMessagePartDraft> drafts, Instant now,
                            boolean appendAnswerPart) {
        this(tenantId, userId, sessionId, messageId, runId, content, drafts, now, 1, appendAnswerPart);
    }

    MessagePartBuildContext {
        startOrder = Math.max(1, startOrder);
    }
}
