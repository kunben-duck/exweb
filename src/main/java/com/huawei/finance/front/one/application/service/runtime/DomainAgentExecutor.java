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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 财经领域 DomainAgent 指定执行器。
 *
 * <p>该执行器只服务前端明确传入 selectedDomainAgentId 的 DomainAgent 路径。它不创建 RuntimeBinding，
 * 也不参与默认复杂任务 Runtime 多轮续接。</p>
 */
@Service
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
                command.message(),
                documents,
                command.metadata(),
                context.forwardHeaders()
        );
        return Flux.concat(Flux.just(selectedDomainAgentEvent(request)),
                concurrencyLimiter.protectAgentRuntime(domainAgentClient.query(request)));
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

    private ChatEvent selectedDomainAgentEvent(DomainAgentRequest request) {
        String domainAgentId = request.domainAgentId() == null ? "" : request.domainAgentId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "selectedDomainAgent");
        payload.put("metadataType", "selected_domain_agent");
        payload.put("routeType", "DOMAIN_AGENT");
        payload.put("domainAgentId", domainAgentId);
        /*
         * 下游 wire contract 仍叫 skillId。历史 parts 同时保留 skillId，方便前端按“技能”
         * 文案展示，也避免调试时需要理解 adapter 内部命名转换。
         */
        payload.put("skillId", domainAgentId);
        payload.put("intentResult", Map.of(
                "accepted", true,
                "source", "front-selected",
                "resourceId", domainAgentId
        ));
        return RuntimeEvent.metadata(request.runId(), request.sessionId(), payload);
    }
}
