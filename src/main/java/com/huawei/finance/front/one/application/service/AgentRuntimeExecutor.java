package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.gateway.AgentRuntime;
import com.huawei.finance.front.one.application.gateway.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 统一 AgentRuntime 执行器。
 *
 * <p>AgentRuntime 只承接复杂任务、低置信任务和无法映射到 SubAgent 的任务。
 * 它是一个完整 Agent 的防腐层入口，内部 session、规划、压缩、上下文演进都由 provider 自己负责；
 * SuperAgent 只通过 runtimeSessionId 做续接，不下沉到 provider 内部实现。</p>
 */
@Service
public class AgentRuntimeExecutor {
    private final AgentRuntimeProviderSelector selector;

    public AgentRuntimeExecutor(AgentRuntimeProviderSelector selector) {
        this.selector = selector;
    }

    public Flux<ChatEvent> execute(ChatCommand command, String runId, MemoryContext memory,
                                   IntentDecision intent, RouteTarget route, UserContext user, AgentBinding binding) {
        List<AttachmentRef> attachments = command.attachments() == null ? List.of() : command.attachments();
        // AgentRuntimeRequest 不再携带旧能力列表。复杂 Agent 需要的外部能力编排应由 Runtime 自己管理，
        // SuperAgent 只传当前用户消息、可见上下文快照、意图/路由信号和上次 runtimeSessionId。
        AgentRuntimeRequest request = new AgentRuntimeRequest(
                user.tenantId(),
                user.userId(),
                command.sessionId(),
                runId,
                binding == null ? null : binding.runtimeSessionId(),
                command.message(),
                command.messageType(),
                command.responseMode(),
                attachments,
                memory,
                intent,
                route,
                command.metadata()
        );
        AgentRuntime runtime = selector.select();
        return runtime.query(request);
    }

    public String configuredProvider() {
        return selector.configuredProvider().configValue();
    }
}
