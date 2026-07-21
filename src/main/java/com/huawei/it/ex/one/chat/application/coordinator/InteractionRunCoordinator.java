package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.IntentClarificationContinuationInput;
import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionClaimResult;
import com.huawei.it.ex.one.chat.application.service.SessionApplicationService;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatInteractionType;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Selects the existing Interaction continuation workflow without changing its state transitions. */
@Component
public class InteractionRunCoordinator {
    private final SessionApplicationService sessionService;
    private final ChatInteractionApplicationService interactionService;
    private final IntentClarificationRunCoordinator intentClarificationCoordinator;
    private final RouteSwitchContinuationCoordinator routeSwitchCoordinator;
    private final RuntimeInteractionContinuationCoordinator runtimeInteractionCoordinator;

    public InteractionRunCoordinator(
            SessionApplicationService sessionService,
            ChatInteractionApplicationService interactionService,
            IntentClarificationRunCoordinator intentClarificationCoordinator,
            RouteSwitchContinuationCoordinator routeSwitchCoordinator,
            RuntimeInteractionContinuationCoordinator runtimeInteractionCoordinator) {
        this.sessionService = sessionService;
        this.interactionService = interactionService;
        this.intentClarificationCoordinator = intentClarificationCoordinator;
        this.routeSwitchCoordinator = routeSwitchCoordinator;
        this.runtimeInteractionCoordinator = runtimeInteractionCoordinator;
    }

    public Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        ChatSession session = sessionService.getSession(request.user(), interaction.sessionId());
        if (request.startAttempt() != null && request.startAttempt().aborted()) {
            interactionService.markWaiting(interaction);
            return Flux.empty();
        }
        if (interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION) {
            if (request.clarificationInput() == null) {
                throw new IllegalStateException("意图澄清 continuation 缺少可信附件解析结果");
            }
            return intentClarificationCoordinator.execute(new IntentClarificationRunCoordinator.Request(
                    request.user(), request.claim(), request.runId(), session, request.forwardHeaders(),
                    request.traceContext(), request.startAttempt(), request.clarificationInput()));
        }
        if (interaction.interactionType() == ChatInteractionType.ROUTE_SWITCH_CONFIRMATION) {
            return routeSwitchCoordinator.execute(new RouteSwitchContinuationCoordinator.Request(
                    request.user(), request.claim(), request.runId(), session, request.forwardHeaders(),
                    request.traceContext(), request.startAttempt()));
        }
        return runtimeInteractionCoordinator.execute(new RuntimeInteractionContinuationCoordinator.Request(
                request.user(), request.claim(), request.runId(), session, request.forwardHeaders(),
                request.traceContext(), request.startAttempt()));
    }

    public record Request(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            IntentClarificationContinuationInput clarificationInput
    ) {
        public Request {
            traceContext = traceContext == null ? TraceContext.empty() : traceContext;
        }
    }
}
