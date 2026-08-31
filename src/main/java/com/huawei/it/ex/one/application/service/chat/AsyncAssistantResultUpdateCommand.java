/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import java.util.List;

/** Updates one async assistant result without reloading the message in the repository layer. */
record AsyncAssistantResultUpdateCommand(
        String tenantId,
        String userId,
        ChatSession session,
        ChatMessage existing,
        String content,
        String resultContent,
        String runId,
        List<ChatMessagePartDraft> partDrafts,
        String metadataJson,
        boolean appendAnswerPart,
        boolean replaceCurrentRunParts
) {
    List<ChatMessagePartDraft> safePartDrafts() {
        return partDrafts == null ? List.of() : List.copyOf(partDrafts);
    }
}
