package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.agent.SubAgentClient;
import com.huawei.finance.front.one.domain.agent.AgentQueryRequest;
import com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 一次性 SubAgent 执行器。
 *
 * <p>SubAgent 只处理用例库或意图服务高置信命中的简单任务。当前正式版本不为 SubAgent 创建绑定，
 * 也不保存下游会话 ID；多轮复杂任务统一交给 Relay Runtime。</p>
 */
@Service
public class SubAgentExecutor {
    private final SubAgentClient subAgentClient;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public SubAgentExecutor(SubAgentClient subAgentClient, WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.subAgentClient = subAgentClient;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    public Flux<ChatEvent> execute(ChatCommand command, String runId, MemoryContext memory, RouteTarget route,
                                   UserContext user) {
        List<AttachmentRef> attachments = command.attachments() == null ? List.of() : command.attachments();
        // AgentQueryRequest 是 SuperAgent 与第三方 SubAgent 的防腐层契约。
        // 对下游只暴露当前消息、上下文快照和附件元信息；
        // 不暴露前端 DTO，也不让 SubAgent 直接读写本服务的会话/记忆存储。
        AgentQueryRequest request = new AgentQueryRequest(
                user.tenantId(),
                user.userId(),
                command.sessionId(),
                runId,
                route.selectedAgentCode(),
                command.message(),
                attachments,
                memory,
                route,
                command.metadata()
        );
        return concurrencyLimiter.protectSubAgent(subAgentClient.query(request));
    }

    /**
     * 尽力取消当前 SubAgent run。
     */
    public Mono<Void> cancel(ChatRun run, UserContext user) {
        if (run == null || run.agentCode() == null || run.agentCode().isBlank()) {
            return Mono.empty();
        }
        SubAgentCancelRequest request = new SubAgentCancelRequest(
                user.tenantId(),
                user.userId(),
                run.sessionId(),
                run.id(),
                run.agentCode(),
                run.cancelReason(),
                Map.of("routeType", run.routeType() == null ? "" : run.routeType())
        );
        return subAgentClient.cancel(request);
    }
}
