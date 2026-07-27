package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ResourceIsolationProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutionContext;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.it.ex.one.application.service.runtime.RuntimeInteractionResponseContext;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.routing.RouteType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps the legacy test constructor's direct DomainAgent executor behavior. */
final class LegacyDomainAgentAwareExecutor extends AgentRuntimeExecutor {
    private final AgentRuntimeExecutor delegate;
    private final DomainAgentExecutor domainAgentExecutor;

    LegacyDomainAgentAwareExecutor(
            AgentRuntimeExecutor delegate,
            DomainAgentExecutor domainAgentExecutor) {
        super(
                (AgentRuntime) null,
                new WorkloadConcurrencyLimiter(new ResourceIsolationProperties()));
        this.delegate = delegate;
        this.domainAgentExecutor = domainAgentExecutor;
    }

    @Override
    public Flux<ChatEvent> execute(RuntimeExecutionContext context) {
        if (context != null
                && context.route() != null
                && context.route().type() == RouteType.DOMAIN_AGENT) {
            return domainAgentExecutor.execute(new DomainAgentExecutionContext(
                    context.command(),
                    context.runId(),
                    context.route(),
                    context.user(),
                    context.binding(),
                    context.forwardHeaders()));
        }
        return delegate.execute(context);
    }

    @Override
    public Flux<ChatEvent> continueWithUserResponse(
            RuntimeInteractionResponseContext context) {
        return delegate.continueWithUserResponse(context);
    }

    @Override
    public boolean supportsWaitingUserResponse(String runtimeProvider) {
        return delegate.supportsWaitingUserResponse(runtimeProvider);
    }

    @Override
    public Mono<Void> cancel(
            ChatRun run,
            UserContext user,
            RuntimeForwardHeaders forwardHeaders) {
        if (run != null
                && RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(
                        run.runtimeProvider())) {
            return domainAgentExecutor.cancel(run, user, forwardHeaders);
        }
        return delegate.cancel(run, user, forwardHeaders);
    }
}
