package com.huawei.it.ex.one.runtime.application.service;

import com.huawei.it.ex.one.common.concurrent.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentExecutionContext;
import com.huawei.it.ex.one.document.application.service.DocumentService;
import com.huawei.it.ex.one.document.application.model.DocumentAttachmentRequest;
import com.huawei.it.ex.one.runtime.application.client.DomainAgentClient;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentRequest;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentSelectionPayload;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentCancelRequest;
import com.huawei.it.ex.one.runtime.application.model.RuntimeDocumentSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRunSnapshot;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.metadata.SelectedIntentContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
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
    private final DocumentService documentFacade;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public DomainAgentExecutor(DomainAgentClient domainAgentClient, DocumentService documentFacade,
                               WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.domainAgentClient = domainAgentClient;
        this.documentFacade = documentFacade;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    public Flux<ChatEvent> execute(DomainAgentExecutionContext context) {
        var command = context.command();
        UserContext user = context.user();
        List<UploadedDocument> uploadedDocuments = command.attachments().isEmpty()
                ? List.of()
                : documentFacade.resolveDocumentsForUser(user, command.attachments().stream()
                        .map(attachment -> new DocumentAttachmentRequest(attachment.documentId()))
                        .toList());
        List<RuntimeDocumentSnapshot> documents = uploadedDocuments.stream()
                .map(this::documentSnapshot)
                .toList();
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

    public Mono<Void> cancel(RuntimeRunSnapshot run, UserContext user, RuntimeForwardHeaders forwardHeaders) {
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
        com.huawei.it.ex.one.runtime.application.model.RuntimeBinding binding = context.binding();
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

    private String bindingRouteSource(com.huawei.it.ex.one.runtime.application.model.RuntimeBinding binding) {
        return binding == null || binding.metadata().get("routeSource") == null
                ? "front-selected"
                : String.valueOf(binding.metadata().get("routeSource"));
    }

    private RuntimeDocumentSnapshot documentSnapshot(UploadedDocument document) {
        return new RuntimeDocumentSnapshot(document.id(), document.tenantId(), document.userId(),
                document.sessionId(), document.originalName(), document.bucket(), document.objectKey(),
                document.contentType(), document.sizeBytes(), document.status(), document.source(),
                document.tokenSize(), document.metadataJson(), document.createdAt(), document.updatedAt());
    }
}
