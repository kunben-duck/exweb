/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

/**
 * Loads the immutable memory snapshot used by one run.
 *
 * <p>The caller controls the invocation point. In the chat flow this assembler is deliberately invoked
 * before admission persists the current user message, so short-term memory continues to exclude that message.</p>
 */
final class RunMemoryContextAssembler {
    private final MemoryApplicationService memoryService;

    RunMemoryContextAssembler(MemoryApplicationService memoryService) {
        this.memoryService = memoryService;
    }

    MemoryContext assemble(ChatCommand command) {
        return memoryService.loadForRun(command);
    }

    MemoryContext assemble(ChatCommand command, String currentLeafMessageId) {
        return memoryService.loadForRun(command, currentLeafMessageId);
    }

    MemoryContext assemble(ChatCommand command, String currentLeafMessageId, boolean emptyShortTermPath) {
        return memoryService.loadForRun(command, currentLeafMessageId, null, emptyShortTermPath);
    }

    MemoryContext assemble(ChatCommand command, String currentLeafMessageId, String excludedMessageId) {
        return memoryService.loadForRun(command, currentLeafMessageId, excludedMessageId);
    }
}
