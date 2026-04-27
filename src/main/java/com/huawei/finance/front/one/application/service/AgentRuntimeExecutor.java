package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.gateway.AgentRuntime;
import com.huawei.finance.front.one.application.gateway.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 统一 AgentRuntime 执行器。
 */
@Service
public class AgentRuntimeExecutor {
    private final AgentRuntimeProviderSelector selector;
    private final ToolCatalogApplicationService toolCatalog;

    public AgentRuntimeExecutor(AgentRuntimeProviderSelector selector, ToolCatalogApplicationService toolCatalog) {
        this.selector = selector;
        this.toolCatalog = toolCatalog;
    }

    public Flux<ChatEvent> execute(ChatCommand command, String runId, MemoryContext memory,
                                   IntentDecision intent, RouteTarget route, UserContext user) {
        List<ToolDefinition> tools = toolCatalog.listAgentVisibleTools(user.tenantId(), user.userId());
        List<AttachmentRef> attachments = command.attachments() == null ? List.of() : command.attachments();
        AgentRuntimeRequest request = new AgentRuntimeRequest(
                user.tenantId(),
                user.userId(),
                command.sessionId(),
                runId,
                null,
                command.message(),
                command.messageType(),
                command.responseMode(),
                attachments,
                memory,
                intent,
                route,
                tools,
                command.metadata()
        );
        AgentRuntime runtime = selector.select();
        return runtime.run(request);
    }
}
