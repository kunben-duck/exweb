package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import java.util.List;
import java.util.Map;

/** Selects the transactional admission service or its existing compatibility path. */
final class ChatRunAdmissionCoordinator {
    private final SessionApplicationService sessionService;
    private final ChatRunApplicationService chatRunService;
    private final ChatInteractionApplicationService interactionService;
    private volatile ChatRunAdmissionCommitService commitService;

    ChatRunAdmissionCoordinator(SessionApplicationService sessionService,
                                ChatRunApplicationService chatRunService,
                                ChatInteractionApplicationService interactionService) {
        this.sessionService = sessionService;
        this.chatRunService = chatRunService;
        this.interactionService = interactionService;
    }

    void setCommitService(ChatRunAdmissionCommitService commitService) {
        this.commitService = commitService;
    }

    boolean transactionalAdmissionAvailable() {
        return commitService != null;
    }

    ChatRunAdmissionCommitService.AdmissionResult admitStandard(
            StandardAdmission request) {
        ChatRunAdmissionCommitService service = commitService;
        if (service == null) {
            return legacyStandardAdmission(request);
        }
        if (request.directDomainAgentWaitBypass()) {
            return service.commitDirectDomainAgent(
                    request.user(),
                    request.command(),
                    request.session(),
                    request.runId(),
                    request.attachments());
        }
        return service.commit(
                request.user(),
                request.command(),
                request.session(),
                request.runId(),
                request.attachments());
    }

    ChatRunAdmissionCommitService.AdmissionResult admitIntentClarification(
            IntentClarificationAdmission request) {
        ChatRunAdmissionCommitService service = commitService;
        if (service == null) {
            return legacyIntentClarificationAdmission(request);
        }
        return service.commitIntentClarification(
                new ChatRunAdmissionCommitService.IntentClarificationAdmissionCommand(
                        request.user(),
                        request.session(),
                        request.runId(),
                        request.interaction(),
                        request.messageText(),
                        request.attachments(),
                        request.runMetadata()));
    }

    private ChatRunAdmissionCommitService.AdmissionResult legacyStandardAdmission(
            StandardAdmission request) {
        ChatRunMessagePlan messagePlan = sessionService.prepareRunMessage(
                request.user(),
                request.command(),
                request.session(),
                request.runId(),
                request.attachments());
        ChatRun run = chatRunService.createRunning(new CreateChatRunContext(
                request.runId(),
                request.user(),
                request.session().id(),
                null,
                null,
                SelectedIntentContext.removeReserved(request.command().metadata()),
                messagePlan.runMode(),
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()));
        return new ChatRunAdmissionCommitService.AdmissionResult(messagePlan, run);
    }

    private ChatRunAdmissionCommitService.AdmissionResult legacyIntentClarificationAdmission(
            IntentClarificationAdmission request) {
        ChatRunMessagePlan messagePlan = sessionService.prepareIntentClarificationAnswer(
                request.user(),
                request.session(),
                request.runId(),
                request.interaction().assistantMessageId(),
                request.messageText(),
                request.attachments());
        ChatRun run = chatRunService.createInteractionRunning(new CreateChatRunContext(
                request.runId(),
                request.user(),
                request.session().id(),
                null,
                null,
                request.runMetadata(),
                com.huawei.it.ex.one.domain.chat.ChatRunMode.NEXT,
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()), request.interaction().id());
        if (interactionService.markAnsweredForRun(request.interaction(), request.runId()) != 1) {
            throw new IllegalStateException(
                    "意图澄清 Interaction 已不再由当前 continuation run 持有");
        }
        return new ChatRunAdmissionCommitService.AdmissionResult(messagePlan, run);
    }

    record StandardAdmission(
            UserContext user,
            ChatCommand command,
            ChatSession session,
            String runId,
            List<AttachmentRef> attachments,
            boolean directDomainAgentWaitBypass
    ) {
    }

    record IntentClarificationAdmission(
            UserContext user,
            ChatSession session,
            String runId,
            ChatInteractionRequest interaction,
            String messageText,
            List<AttachmentRef> attachments,
            Map<String, Object> runMetadata
    ) {
    }
}
