package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.chat.application.service.ChatStreamApplicationService;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import org.springframework.stereotype.Component;

/** Runs the shared tail of existing committed-event post-processing in its original order. */
@Component
public class CommittedChatEventObserver {
    private final ChatRunExecutionTerminalMarker executionTerminalMarker;
    private final RuntimeBindingService runtimeBindingService;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunCompletionCoordinator completionCoordinator;

    public CommittedChatEventObserver(ChatRunExecutionTerminalMarker executionTerminalMarker,
                                      RuntimeBindingService runtimeBindingService,
                                      ChatStreamApplicationService chatStreamService,
                                      ChatRunCompletionCoordinator completionCoordinator) {
        this.executionTerminalMarker = executionTerminalMarker;
        this.runtimeBindingService = runtimeBindingService;
        this.chatStreamService = chatStreamService;
        this.completionCoordinator = completionCoordinator;
    }

    public RuntimeBinding observeBindingAndPublish(ChatEvent stored,
                                                   RunEventPipelineContext context,
                                                   RuntimeBinding binding) {
        executionTerminalMarker.markIfTerminal(stored);
        RuntimeBinding observed = runtimeBindingService.observeEvent(binding, stored);
        context.bindingRef().set(observed);
        chatStreamService.publishPersisted(stored);
        completionCoordinator.recordRouteMemoryAfterCommitted(stored, context);
        return observed;
    }
}
