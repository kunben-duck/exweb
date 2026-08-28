package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;

/** 已在stop前完成归属和附件校验的候选切换source快照。 */
record CandidateSwitchRunSource(
        String sourceRunId,
        ChatRunStatus sourceRunStatus,
        ChatSession session,
        ChatMessage userMessage,
        String assistantMessageId,
        ResolvedChatAttachments resolvedAttachments
) {}
