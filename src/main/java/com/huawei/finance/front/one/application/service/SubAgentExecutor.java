package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.gateway.SubAgentClient;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.agent.AgentQueryRequest;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class SubAgentExecutor {
    private final SubAgentClient subAgentClient;

    public SubAgentExecutor(SubAgentClient subAgentClient) {
        this.subAgentClient = subAgentClient;
    }

    public Flux<ChatEvent> execute(ChatCommand command, String runId, MemoryContext memory, RouteTarget route,
                                   UserContext user, AgentBinding binding) {
        List<AttachmentRef> attachments = command.attachments() == null ? List.of() : command.attachments();
        // AgentQueryRequest 是 SuperAgent 与第三方 SubAgent 的防腐层契约。
        // 对下游只暴露当前消息、上下文快照、附件元信息和已保存的 agentSessionId；
        // 不暴露前端 DTO，也不让 SubAgent 直接读写本服务的会话/记忆存储。
        AgentQueryRequest request = new AgentQueryRequest(
                user.tenantId(),
                user.userId(),
                command.sessionId(),
                runId,
                route.selectedAgentCode(),
                binding == null ? null : binding.agentSessionId(),
                null,
                command.message(),
                command.messageType(),
                command.responseMode(),
                attachments,
                memory,
                route,
                command.metadata()
        );
        return subAgentClient.query(request);
    }
}
