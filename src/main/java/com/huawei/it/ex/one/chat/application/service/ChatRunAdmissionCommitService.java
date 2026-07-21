package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.model.InteractionMessageStrategy;
import com.huawei.it.ex.one.common.metadata.SelectedIntentContext;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.ChatRunMode;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原子提交普通 run 的用户消息树变更和 RUNNING 记录。
 *
 * <p>数据库 active-run 唯一索引是最终准入栅栏。并发失败时整个事务回滚，避免留下
 * 没有关联 run 的用户消息、附件关系或 current leaf。</p>
 */
@Service
public class ChatRunAdmissionCommitService {
    private final SessionApplicationService sessionService;
    private final ChatRunApplicationService chatRunService;
    private final ChatInteractionApplicationService interactionService;
    private final RuntimeBindingService runtimeBindingService;

    public ChatRunAdmissionCommitService(SessionApplicationService sessionService,
                                         ChatRunApplicationService chatRunService,
                                         ChatInteractionApplicationService interactionService,
                                         RuntimeBindingService runtimeBindingService) {
        this.sessionService = sessionService;
        this.chatRunService = chatRunService;
        this.interactionService = interactionService;
        this.runtimeBindingService = runtimeBindingService;
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commit(UserContext user, ChatCommand command, ChatSession session,
                                  String runId, List<AttachmentRef> attachments) {
        return commitRun(user, command, session, runId, attachments);
    }

    /**
     * 原子受理前端直连 DomainAgent：提交当前 user 消息和 RUNNING run 后，再取消等待态与旧绑定。
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitDirectDomainAgent(UserContext user, ChatCommand command, ChatSession session,
                                                   String runId, List<AttachmentRef> attachments) {
        if (interactionService == null) {
            throw new IllegalStateException("DomainAgent 直连 admission 缺少 Interaction 服务");
        }
        if (runtimeBindingService == null) {
            throw new IllegalStateException("DomainAgent 直连 admission 缺少 RuntimeBinding 服务");
        }
        sessionService.lockForMessageMutation(user.tenantId(), user.ownerUserId(), session);
        AdmissionResult admission = commitRun(user, command, session, runId, attachments);
        interactionService.cancelOpenBySession(user, session.id());
        List<RuntimeBinding> cancelledBindings = runtimeBindingService.cancelActiveForAdmission(
                user.tenantId(), user.ownerUserId(), session.id());
        return new AdmissionResult(admission.messagePlan(), admission.run(), cancelledBindings);
    }

    private AdmissionResult commitRun(UserContext user, ChatCommand command, ChatSession session,
                                      String runId, List<AttachmentRef> attachments) {
        ChatRunMessagePlan messagePlan = sessionService.prepareRunMessage(
                user, command, session, runId, attachments);
        ChatRun run = chatRunService.insertRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                null,
                null,
                SelectedIntentContext.removeReserved(command.metadata()),
                messagePlan.runMode(),
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()
        ));
        return new AdmissionResult(messagePlan, run);
    }

    /**
     * 原子受理意图澄清回答：新 user 节点、continuation run 和旧 Interaction ANSWERED 同时成立。
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitIntentClarification(IntentClarificationAdmissionCommand command) {
        UserContext user = command.user();
        ChatSession session = command.session();
        String runId = command.runId();
        ChatInteractionRequest interaction = command.interaction();
        if (!InteractionMessageStrategy.newTurn(interaction)) {
            throw new IllegalArgumentException("仅 INTENT_CLARIFICATION 支持独立消息 admission");
        }
        ChatRunMessagePlan messagePlan = sessionService.prepareIntentClarificationAnswer(
                new IntentClarificationAnswerCommand(
                        user, session, runId, interaction.assistantMessageId(),
                        command.answerText(), command.attachments()));
        ChatRun run = chatRunService.insertInteractionRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                null,
                null,
                command.runMetadata(),
                ChatRunMode.NEXT,
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()
        ), interaction.id());
        int answered = interactionService.markAnsweredForRun(interaction, runId);
        if (answered != 1) {
            throw new IllegalStateException("意图澄清 Interaction 已不再由当前 continuation run 持有");
        }
        return new AdmissionResult(messagePlan, run);
    }

    public record IntentClarificationAdmissionCommand(
            UserContext user,
            ChatSession session,
            String runId,
            ChatInteractionRequest interaction,
            String answerText,
            List<AttachmentRef> attachments,
            java.util.Map<String, Object> runMetadata
    ) {
        public IntentClarificationAdmissionCommand {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            runMetadata = runMetadata == null ? java.util.Map.of() : java.util.Map.copyOf(runMetadata);
        }

        public IntentClarificationAdmissionCommand(UserContext user, ChatSession session, String runId,
                                                   ChatInteractionRequest interaction, String answerText,
                                                   java.util.Map<String, Object> runMetadata) {
            this(user, session, runId, interaction, answerText, List.of(), runMetadata);
        }
    }

    public record AdmissionResult(ChatRunMessagePlan messagePlan, ChatRun run,
                                  List<RuntimeBinding> cancelledBindings) {
        public AdmissionResult {
            cancelledBindings = cancelledBindings == null ? List.of() : List.copyOf(cancelledBindings);
        }

        public AdmissionResult(ChatRunMessagePlan messagePlan, ChatRun run) {
            this(messagePlan, run, List.of());
        }
    }
}
