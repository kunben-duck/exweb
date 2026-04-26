package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.gateway.AgentRunRequest;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 本地 Agent 执行器。
 *
 * <p>负责把应用层 ChatCommand 转换成 AgentRunRequest，并附带当前用户可见工具列表。</p>
 */
@Service
public class LocalAgentExecutor {
    private final AgentEngineRouter router;
    private final ToolCatalogApplicationService toolCatalog;
    public LocalAgentExecutor(AgentEngineRouter router, ToolCatalogApplicationService toolCatalog) { this.router = router; this.toolCatalog = toolCatalog; }
    public Flux<ChatEvent> execute(ChatCommand command, String runId, MemoryContext memory, IntentDecision intent, UserContext user) {
        // 只把调用者有权限看到的工具交给 Agent，降低越权调用风险。
        List<ToolDefinition> tools = toolCatalog.listAgentVisibleTools(user.tenantId(), user.userId());
        List<AttachmentRef> attachments = command.attachments() == null ? List.of() : command.attachments();
        AgentRunRequest request = new AgentRunRequest(user.tenantId(), user.userId(), command.sessionId(), runId, command.message(), command.messageType(), command.responseMode(), attachments, memory, intent, tools, Map.of(), command.metadata());
        return router.run(request);
    }
}
