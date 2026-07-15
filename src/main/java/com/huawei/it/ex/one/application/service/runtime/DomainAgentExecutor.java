package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentSelectionPayload;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 财经领域 DomainAgent 旧执行器。
 *
 * <p>生产主流程已收敛到 {@code DomainAgentRuntime}，该类保留给历史测试夹具和过渡代码使用，
 * 不再注册为 Spring bean。</p>
 */
@Deprecated(forRemoval = false)
public class DomainAgentExecutor {
    private final DomainAgentClient domainAgentClient;
    private final DocumentFacade documentFacade;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public DomainAgentExecutor(DomainAgentClient domainAgentClient, DocumentFacade documentFacade,
                               WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.domainAgentClient = domainAgentClient;
        this.documentFacade = documentFacade;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    public Flux<ChatEvent> execute(DomainAgentExecutionContext context) {
        var command = context.command();
        UserContext user = context.user();
        List<UploadedDocument> documents = command.attachments().isEmpty()
                ? List.of()
                : documentFacade.resolveDocumentsForUser(user, command.attachments());
        DomainAgentRequest request = new DomainAgentRequest(
                user,
                command.sessionId(),
                context.runId(),
                context.route().selectedAgentCode(),
                context.binding() == null ? command.sessionId() : context.binding().runtimeSessionId(),
                command.message(),
                documents,
                SelectedIntentContext.removeReserved(command.metadata()),
                context.forwardHeaders()
        );
        return Flux.concat(Flux.just(selectedDomainAgentEvent(request, context)),
                concurrencyLimiter.protectDomainAgent(domainAgentClient.query(request)));
    }

    public Mono<Void> cancel(ChatRun run, UserContext user, RuntimeForwardHeaders forwardHeaders) {
        if (run == null || run.agentCode() == null || run.agentCode().isBlank()) {
            return Mono.empty();
        }
        return domainAgentClient.cancel(new DomainAgentCancelRequest(
                user,
                run.sessionId(),
                run.id(),
                run.agentCode(),
                run.cancelReason(),
                Map.of("routeType", run.routeType() == null ? "" : run.routeType()),
                forwardHeaders
        ));
    }

    private ChatEvent selectedDomainAgentEvent(DomainAgentRequest request, DomainAgentExecutionContext context) {
        com.huawei.it.ex.one.domain.runtime.RuntimeBinding binding = context.binding();
        String domainAgentId = request.domainAgentId() == null ? "" : request.domainAgentId();
        String routeSource = context.route() == null || context.route().routeSource() == null
                ? bindingRouteSource(binding)
                : context.route().routeSource();
        String runtimeSessionId = request.runtimeSessionId() == null || request.runtimeSessionId().isBlank()
                ? request.sessionId()
                : request.runtimeSessionId();
        return RuntimeEvent.metadata(request.runId(), request.sessionId(),
                DomainAgentSelectionPayload.create(domainAgentId, routeSource, runtimeSessionId, null,
                        binding == null ? Map.of() : binding.metadata()));
    }

    private String bindingRouteSource(com.huawei.it.ex.one.domain.runtime.RuntimeBinding binding) {
        return binding == null || binding.metadata().get("routeSource") == null
                ? "front-selected"
                : String.valueOf(binding.metadata().get("routeSource"));
    }
}
