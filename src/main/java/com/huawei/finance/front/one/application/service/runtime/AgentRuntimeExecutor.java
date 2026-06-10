package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 统一 AgentRuntime 执行器。
 *
 * <p>该类只依赖 AgentRuntime 防腐层接口，不关心底层实现是 Relay、HTTP、gRPC 还是其他企业内部
 * Runtime。当前上线版本通过配置默认装配 Relay adapter；简单任务如果命中 SubAgent 会一次性执行，
 * 其他复杂、低置信或未命中的请求都会进入当前 AgentRuntime，并通过 RuntimeBinding 保持多轮会话。</p>
 */
@Service
public class AgentRuntimeExecutor {
    private final AgentRuntime runtime;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public AgentRuntimeExecutor(AgentRuntime runtime, WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.runtime = runtime;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    public Flux<ChatEvent> execute(ChatCommand command, String runId, MemoryContext memory,
                                   IntentDecision intent, RouteTarget route, UserContext user, RuntimeBinding binding,
                                   RuntimeForwardHeaders forwardHeaders) {
        List<AttachmentRef> attachments = command.attachments() == null ? List.of() : command.attachments();
        // AgentRuntimeRequest 不再携带旧能力列表。复杂 Agent 需要的外部能力编排应由 Runtime 自己管理，
        // SuperAgent 只传当前用户消息、可见上下文快照、意图/路由信号和上次 runtimeSessionId。
        // forwardHeaders 仅为运行期内存快照，Relay adapter 会按白名单决定是否放入出站请求头。
        AgentRuntimeRequest request = new AgentRuntimeRequest(
                user.tenantId(),
                user.userId(),
                command.sessionId(),
                runId,
                binding == null ? null : binding.runtimeSessionId(),
                command.message(),
                attachments,
                memory,
                intent,
                route,
                command.metadata(),
                forwardHeaders
        );
        return concurrencyLimiter.protectAgentRuntime(runtime.query(request));
    }

    /**
     * 尽力取消当前 Runtime run。
     */
    public Mono<Void> cancel(ChatRun run, UserContext user, RuntimeForwardHeaders forwardHeaders) {
        if (run == null) {
            return Mono.empty();
        }
        AgentRuntimeCancelRequest request = new AgentRuntimeCancelRequest(
                user.tenantId(),
                user.userId(),
                run.sessionId(),
                run.id(),
                run.runtimeSessionId(),
                run.runtimeProvider(),
                run.cancelReason(),
                Map.of("routeType", run.routeType() == null ? "" : run.routeType()),
                forwardHeaders
        );
        return runtime.cancel(request);
    }
}
