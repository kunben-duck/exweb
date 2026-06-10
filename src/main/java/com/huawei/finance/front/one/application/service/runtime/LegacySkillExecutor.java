package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentClient;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 显式技能执行器。
 *
 * <p>该执行器只服务前端明确传入 selectedSkillId 的历史技能兼容路径。它不创建 RuntimeBinding，
 * 也不参与默认复杂任务 Runtime 多轮续接。</p>
 */
@Service
public class LegacySkillExecutor {
    private final LegacySkillAgentClient legacySkillAgentClient;
    private final DocumentFacade documentFacade;
    private final WorkloadConcurrencyLimiter concurrencyLimiter;

    public LegacySkillExecutor(LegacySkillAgentClient legacySkillAgentClient, DocumentFacade documentFacade,
                               WorkloadConcurrencyLimiter concurrencyLimiter) {
        this.legacySkillAgentClient = legacySkillAgentClient;
        this.documentFacade = documentFacade;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    public Flux<ChatEvent> execute(ChatCommand command, String runId, RouteTarget route, UserContext user,
                                   RuntimeForwardHeaders forwardHeaders) {
        List<UploadedDocument> documents = documentFacade.resolveDocumentsForUser(user, command.attachments());
        LegacySkillAgentRequest request = new LegacySkillAgentRequest(
                user,
                command.sessionId(),
                runId,
                route.selectedAgentCode(),
                command.message(),
                documents,
                command.metadata(),
                forwardHeaders
        );
        return concurrencyLimiter.protectAgentRuntime(legacySkillAgentClient.query(request));
    }

    public Mono<Void> cancel(ChatRun run, UserContext user, RuntimeForwardHeaders forwardHeaders) {
        if (run == null || run.agentCode() == null || run.agentCode().isBlank()) {
            return Mono.empty();
        }
        return legacySkillAgentClient.cancel(new LegacySkillCancelRequest(
                user,
                run.sessionId(),
                run.id(),
                run.agentCode(),
                run.cancelReason(),
                Map.of("routeType", run.routeType() == null ? "" : run.routeType()),
                forwardHeaders
        ));
    }
}
