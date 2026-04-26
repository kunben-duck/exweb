package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.gateway.AgentRuntimeClient;
import com.huawei.finance.front.one.application.gateway.RuntimeRequest;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RuntimeProtocol;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Relay Runtime 转发服务。
 *
 * <p>复杂任务由这里转成 RuntimeRequest，再根据协议选择真实 Runtime 实现。</p>
 */
@Service
public class RuntimeRelayService {
    private final List<AgentRuntimeClient> runtimePorts;
    private final ToolCatalogApplicationService toolCatalog;
    public RuntimeRelayService(List<AgentRuntimeClient> runtimePorts, ToolCatalogApplicationService toolCatalog) {
        this.runtimePorts = runtimePorts; this.toolCatalog = toolCatalog;
    }
    public Flux<ChatEvent> relay(ChatCommand command, String runId, MemoryContext memory, IntentDecision intent, UserContext user, RuntimeProtocol protocol) {
        // Runtime 和本地 Agent 使用同一份工具可见性规则，保证执行边界一致。
        List<ToolDefinition> tools = toolCatalog.listAgentVisibleTools(user.tenantId(), user.userId());
        List<AttachmentRef> attachments = command.attachments() == null ? List.of() : command.attachments();
        RuntimeRequest request = new RuntimeRequest(user.tenantId(), user.userId(), command.sessionId(), runId, command.message(), command.messageType(), command.responseMode(), attachments, memory, intent, tools, command.metadata());
        // 通过 RuntimeProtocol 解耦 HTTP Stream、WebSocket 或后续其他 Runtime 协议。
        AgentRuntimeClient port = runtimePorts.stream().filter(p -> p.supports(protocol)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No runtime implementor for " + protocol));
        return port.stream(request);
    }
}
