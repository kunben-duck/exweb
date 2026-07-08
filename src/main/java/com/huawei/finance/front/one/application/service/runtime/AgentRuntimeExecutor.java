package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeHitlResponseRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 统一 AgentRuntime 执行器。
 *
 * <p>该类只依赖 AgentRuntime 防腐层接口，不关心底层实现是 Relay、HTTP、gRPC 还是其他企业内部
 * Runtime。普通问答通过 {@link AgentRuntime}，协议级澄清/审批续接通过
 * {@link AgentRuntimeInteraction}；两者分开可以避免把可选交互能力塞进 Runtime 主接口。当前上线版本
 * 通过配置默认装配 Relay adapter；没有命中 DomainAgent 绑定或路由的请求会进入当前 AgentRuntime，
 * 并通过 RuntimeBinding 保持多轮会话。</p>
 */
@Service
public class AgentRuntimeExecutor {
    private static final String DOMAIN_AGENT_PROVIDER = "domain-agent";

    private final AgentRuntimeRegistry runtimeRegistry;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public AgentRuntimeExecutor(AgentRuntime runtime, WorkloadConcurrencyLimiter concurrencyLimiter) {
        this(new AgentRuntimeRegistry(runtime == null ? List.of() : List.of(runtime),
                        runtime == null ? "relay" : runtime.provider()),
                concurrencyLimiter);
    }

    public AgentRuntimeExecutor(AgentRuntime runtime, AgentRuntimeInteraction runtimeInteraction,
                                WorkloadConcurrencyLimiter concurrencyLimiter) {
        this(runtimeInteraction == null ? runtime : new RuntimeWithInteraction(runtime, runtimeInteraction),
                concurrencyLimiter);
    }

    @Autowired
    public AgentRuntimeExecutor(AgentRuntimeRegistry runtimeRegistry, WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.runtimeRegistry = runtimeRegistry;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    public Flux<ChatEvent> execute(RuntimeExecutionContext context) {
        var command = context.command();
        var user = context.user();
        var binding = context.binding();
        List<AttachmentRef> attachments = command.attachments() == null ? List.of() : command.attachments();
        // AgentRuntimeRequest 不再携带旧能力列表。复杂 Agent 需要的外部能力编排应由 Runtime 自己管理，
        // SuperAgent 只传当前用户消息、可见上下文快照、意图/路由信号和上次 runtimeSessionId。
        // forwardHeaders 仅为运行期内存快照，Relay adapter 会按白名单决定是否放入出站请求头。
        AgentRuntimeRequest request = new AgentRuntimeRequest(
                user.tenantId(),
                user.ownerUserId(),
                user.userAccount(),
                user.globalUserId(),
                command.sessionId(),
                context.runId(),
                binding == null ? null : binding.runtimeSessionId(),
                context.runtimeSessionMode(),
                command.message(),
                attachments,
                context.documents(),
                context.memory(),
                context.intent(),
                context.route(),
                command.metadata(),
                context.forwardHeaders()
        );
        AgentRuntime runtime = context.binding() == null
                ? runtimeRegistry.defaultRuntime()
                : runtimeRegistry.runtime(context.binding().provider());
        return protect(runtime.provider(), runtime.query(request));
    }

    public Flux<ChatEvent> continueWithUserResponse(RuntimeHitlResponseContext context) {
        var request = new AgentRuntimeHitlResponseRequest(
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.user().userAccount(),
                context.user().globalUserId(),
                context.sessionId(),
                context.runId(),
                context.runtimeSessionId(),
                context.runtimeProvider(),
                context.hitlRequestId(),
                context.waitingType(),
                context.approvalId(),
                context.responsePayload(),
                context.forwardHeaders()
        );
        return protect(context.runtimeProvider(), runtimeRegistry.continueWithUserResponse(request));
    }

    public boolean supportsWaitingUserResponse(String runtimeProvider) {
        return runtimeRegistry.supportsWaitingUserResponse(runtimeProvider);
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
                user.ownerUserId(),
                run.sessionId(),
                run.id(),
                run.runtimeSessionId(),
                run.runtimeProvider(),
                run.agentCode(),
                run.cancelReason(),
                Map.of("routeType", run.routeType() == null ? "" : run.routeType()),
                forwardHeaders
        );
        return runtimeRegistry.runtime(run.runtimeProvider()).cancel(request);
    }

    private Flux<ChatEvent> protect(String provider, Flux<ChatEvent> source) {
        return DOMAIN_AGENT_PROVIDER.equalsIgnoreCase(provider)
                ? concurrencyLimiter.protectDomainAgent(source)
                : concurrencyLimiter.protectAgentRuntime(source);
    }

    private record RuntimeWithInteraction(AgentRuntime runtime, AgentRuntimeInteraction interaction)
            implements AgentRuntime, AgentRuntimeInteraction {
        @Override
        public String provider() {
            return runtime == null ? "relay" : runtime.provider();
        }

        @Override
        public Flux<ChatEvent> query(AgentRuntimeRequest request) {
            return runtime == null ? Flux.empty() : runtime.query(request);
        }

        @Override
        public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
            return runtime == null ? Mono.empty() : runtime.cancel(request);
        }

        @Override
        public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeHitlResponseRequest request) {
            return interaction.continueWithUserResponse(request);
        }

        @Override
        public boolean supportsWaitingUserResponse(String runtimeProvider) {
            return interaction.supportsWaitingUserResponse(runtimeProvider);
        }
    }
}
