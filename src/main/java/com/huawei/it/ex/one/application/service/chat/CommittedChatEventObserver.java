/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

/** Runs the shared tail of committed-event post-processing in its original order. */
final class CommittedChatEventObserver {
    private final ChatRunExecutionTerminalMarker executionTerminalMarker;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunCompletionCoordinator completionCoordinator;

    CommittedChatEventObserver(ChatRunExecutionTerminalMarker executionTerminalMarker,
                               RuntimeBindingApplicationService runtimeBindingService,
                               ChatStreamApplicationService chatStreamService,
                               ChatRunCompletionCoordinator completionCoordinator) {
        this.executionTerminalMarker = executionTerminalMarker;
        this.runtimeBindingService = runtimeBindingService;
        this.chatStreamService = chatStreamService;
        this.completionCoordinator = completionCoordinator;
    }

    RuntimeBinding observeBindingAndPublish(ChatEvent stored,
                                            RunEventPipelineContext context,
                                            RuntimeBinding binding) {
        executionTerminalMarker.markIfTerminal(stored);
        RuntimeBinding observed = runtimeBindingService.observeEvent(binding, stored);
        context.bindingRef().set(observed);
        completionCoordinator.recordRouteMemoryAfterCommitted(stored, context);
        chatStreamService.publishPersisted(stored);
        return observed;
    }
}
