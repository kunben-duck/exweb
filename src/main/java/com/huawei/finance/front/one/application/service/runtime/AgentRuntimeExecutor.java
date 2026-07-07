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
 * 通过配置默认装配 Relay adapter；简单任务如果命中 SubAgent 会一次性执行，其他复杂、低置信或未命中的
 * 请求都会进入当前 AgentRuntime，并通过 RuntimeBinding 保持多轮会话。</p>
 */
@Service
public class AgentRuntimeExecutor {
    private final AgentRuntime runtime;
    private final AgentRuntimeInteraction runtimeInteraction;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public AgentRuntimeExecutor(AgentRuntime runtime, WorkloadConcurrencyLimiter concurrencyLimiter) {
        this(runtime, unsupportedInteraction(), concurrencyLimiter);
    }

    @Autowired
    public AgentRuntimeExecutor(AgentRuntime runtime, AgentRuntimeInteraction runtimeInteraction,
                                WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.runtime = runtime;
        this.runtimeInteraction = runtimeInteraction;
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
                context.memory(),
                context.intent(),
                context.route(),
                command.metadata(),
                context.forwardHeaders()
        );
        return concurrencyLimiter.protectAgentRuntime(runtime.query(request));
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
        return concurrencyLimiter.protectAgentRuntime(runtimeInteraction.continueWithUserResponse(request));
    }

    public boolean supportsWaitingUserResponse(String runtimeProvider) {
        return runtimeInteraction.supportsWaitingUserResponse(runtimeProvider);
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
                run.cancelReason(),
                Map.of("routeType", run.routeType() == null ? "" : run.routeType()),
                forwardHeaders
        );
        return runtime.cancel(request);
    }

    private static AgentRuntimeInteraction unsupportedInteraction() {
        return new AgentRuntimeInteraction() {
            @Override
            public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeHitlResponseRequest request) {
                return Flux.error(new UnsupportedOperationException("当前 AgentRuntime 不支持交互续接"));
            }
        };
    }
}
