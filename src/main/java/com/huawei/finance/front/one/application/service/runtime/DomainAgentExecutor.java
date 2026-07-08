package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentClient;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.LinkedHashMap;
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
        List<UploadedDocument> documents = documentFacade.resolveDocumentsForUser(user, command.attachments());
        DomainAgentRequest request = new DomainAgentRequest(
                user,
                command.sessionId(),
                context.runId(),
                context.route().selectedAgentCode(),
                context.binding() == null ? command.sessionId() : context.binding().runtimeSessionId(),
                command.message(),
                documents,
                command.metadata(),
                context.forwardHeaders()
        );
        return Flux.concat(Flux.just(selectedDomainAgentEvent(request, context.binding())),
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

    private ChatEvent selectedDomainAgentEvent(DomainAgentRequest request,
                                               com.huawei.finance.front.one.domain.runtime.RuntimeBinding binding) {
        String domainAgentId = request.domainAgentId() == null ? "" : request.domainAgentId();
        String routeSource = binding == null || binding.metadata().get("routeSource") == null
                ? "front-selected"
                : String.valueOf(binding.metadata().get("routeSource"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "selectedDomainAgent");
        payload.put("metadataType", "selected_domain_agent");
        payload.put("routeType", "DOMAIN_AGENT");
        payload.put("targetType", "DOMAIN_AGENT");
        payload.put("targetId", domainAgentId);
        payload.put("domainAgentId", domainAgentId);
        payload.put("routeSource", routeSource);
        payload.put("runtimeSessionId", request.runtimeSessionId() == null || request.runtimeSessionId().isBlank()
                ? request.sessionId()
                : request.runtimeSessionId());
        payload.put("intentResult", Map.of(
                "accepted", true,
                "source", routeSource,
                "resourceId", domainAgentId
        ));
        return RuntimeEvent.metadata(request.runId(), request.sessionId(), payload);
    }
}
