package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService.AdmissionCancellation;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ActiveRunExistsException;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.CandidateSwitchConflictException;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    private final RuntimeBindingApplicationService runtimeBindingService;

    public ChatRunAdmissionCommitService(SessionApplicationService sessionService,
                                         ChatRunApplicationService chatRunService,
                                         ChatInteractionApplicationService interactionService,
                                         RuntimeBindingApplicationService runtimeBindingService) {
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

    /** 原子受理前端直连 Runtime：提交当前 user 消息和 RUNNING run 后，再取消等待态与冲突绑定。 */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitDirectRuntime(DirectRuntimeAdmissionCommand request) {
        if (request == null) {
            throw new IllegalArgumentException("Runtime 直连 admission 参数不能为空");
        }
        if (interactionService == null) {
            throw new IllegalStateException("Runtime 直连 admission 缺少 Interaction 服务");
        }
        if (runtimeBindingService == null) {
            throw new IllegalStateException("Runtime 直连 admission 缺少 RuntimeBinding 服务");
        }
        UserContext user = request.user();
        ChatCommand command = request.command();
        ChatSession session = request.session();
        String runId = request.runId();
        List<AttachmentRef> attachments = request.attachments();
        ExplicitRuntimeTarget explicitTarget = request.explicitTarget();
        if (explicitTarget == null) {
            throw new IllegalArgumentException("Runtime 直连目标不能为空");
        }
        sessionService.lockForMessageMutation(user.tenantId(), user.ownerUserId(), session);
        AdmissionResult admission = commitRun(user, command, session, runId, attachments);
        int cancelledInteractions = interactionService.cancelOpenBySessionAndCount(user, session.id());
        boolean deferDomainAgentBinding = explicitTarget.domainAgent() && !attachments.isEmpty();
        List<AdmissionCancellation> bindingCancellations = deferDomainAgentBinding
                ? List.of()
                : explicitTarget.domainExpert() && cancelledInteractions == 0
                        ? runtimeBindingService.cancelActiveForAdmissionExceptPinnedDomainExpertWithSnapshots(
                                user.tenantId(), user.ownerUserId(), session.id(), explicitTarget.targetId())
                        : runtimeBindingService.cancelActiveForAdmissionWithSnapshots(
                                user.tenantId(), user.ownerUserId(), session.id());
        return new AdmissionResult(admission.messagePlan(), admission.run(), bindingCancellations);
    }

    /** 兼容现有内部调用与事务契约测试。 */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitDirectDomainAgent(UserContext user, ChatCommand command, ChatSession session,
                                                   String runId, List<AttachmentRef> attachments) {
        return commitDirectRuntime(new DirectRuntimeAdmissionCommand(
                user, command, session, runId, attachments,
                new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_AGENT, command.targetId())));
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

    /** 原子受理候选DomainAgent切换：复用source user消息并替换冲突Binding。 */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitCandidateSwitch(CandidateSwitchAdmissionCommand request) {
        if (request == null || request.source() == null) {
            throw new IllegalArgumentException("候选技能切换admission参数不能为空");
        }
        UserContext user = request.user();
        ChatSession session = request.source().session();
        sessionService.lockForMessageMutation(
                user.tenantId(), user.ownerUserId(), session);
        ChatSession currentSession = sessionService.requireSessionForInternalUpdate(
                user.tenantId(), user.ownerUserId(), session.id());
        try {
            chatRunService.rejectIfActiveRunExists(user, currentSession.id());
        } catch (ActiveRunExistsException ex) {
            throw CandidateSwitchConflictException.staleSource(request.source().sourceRunId());
        }
        ChatRunMessagePlan messagePlan = sessionService.prepareCandidateSwitchPlan(
                user,
                currentSession,
                request.source().sourceRunId(),
                request.source().userMessage().id(),
                request.source().assistantMessageId());
        ChatRun run;
        try {
            run = chatRunService.insertRunning(new CreateChatRunContext(
                    request.runId(),
                    user,
                    currentSession.id(),
                    null,
                    null,
                    SelectedIntentContext.removeReserved(request.command().metadata()),
                    messagePlan.runMode(),
                    messagePlan.parentMessageId(),
                    messagePlan.userMessage().id()));
        } catch (ActiveRunExistsException ex) {
            throw CandidateSwitchConflictException.staleSource(request.source().sourceRunId());
        }
        interactionService.cancelOpenBySessionAndCount(user, currentSession.id());
        List<AttachmentRef> requestedAttachments = request.command().attachments() == null
                ? List.of()
                : request.command().attachments();
        List<AdmissionCancellation> bindingCancellations = requestedAttachments.isEmpty()
                ? runtimeBindingService.cancelActiveForAdmissionWithSnapshots(
                        user.tenantId(), user.ownerUserId(), currentSession.id())
                : List.of();
        return new AdmissionResult(messagePlan, run, bindingCancellations);
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
                user, session, runId, interaction.assistantMessageId(), command.answerText(), command.attachments());
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

    /**
     * 原子受理 AMBIGUOUS_ROUTE 续接：复用原消息、追加附件并创建 continuation run。
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitReusableIntentClarification(IntentClarificationAdmissionCommand command) {
        UserContext user = command.user();
        ChatSession session = command.session();
        String runId = command.runId();
        ChatInteractionRequest interaction = command.interaction();
        if (InteractionMessageStrategy.newTurn(interaction)) {
            throw new IllegalArgumentException("普通 INTENT_CLARIFICATION 不能复用 assistant 消息");
        }
        sessionService.lockForMessageMutation(
                user.tenantId(), user.ownerUserId(), session);
        ChatRunMessagePlan messagePlan = sessionService.prepareReusableIntentClarification(
                user,
                session,
                runId,
                interaction,
                command.attachments());
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

    record DirectRuntimeAdmissionCommand(
            UserContext user,
            ChatCommand command,
            ChatSession session,
            String runId,
            List<AttachmentRef> attachments,
            ExplicitRuntimeTarget explicitTarget
    ) {
        DirectRuntimeAdmissionCommand {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    record CandidateSwitchAdmissionCommand(
            UserContext user,
            ChatCommand command,
            String runId,
            CandidateSwitchRunSource source
    ) {
    }

    public record AdmissionResult(ChatRunMessagePlan messagePlan, ChatRun run,
                                  List<AdmissionCancellation> bindingCancellations) {
        public AdmissionResult {
            bindingCancellations = bindingCancellations == null
                    ? List.of()
                    : List.copyOf(bindingCancellations);
        }

        public AdmissionResult(ChatRunMessagePlan messagePlan, ChatRun run) {
            this(messagePlan, run, List.of());
        }

        public List<RuntimeBinding> cancelledBindings() {
            return bindingCancellations.stream().map(AdmissionCancellation::cancelled).toList();
        }
    }
}
