/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
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
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
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
        ChatSession current = sessionService.lockAndReloadForMessageMutation(
                user.tenantId(), user.ownerUserId(), session);
        IntentExpertScope scope = IntentExpertContext.fromSessionMetadata(current.metadataJson()).orElse(null);
        return commitRun(new RunAdmissionContext(
                user, command, current, runId, attachments, scope, false));
    }

    /** 原子选择或切换聚合意图专家范围，并受理本轮Run。 */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitIntentExpert(DirectRuntimeAdmissionCommand request) {
        if (request == null || request.explicitTarget() == null
                || !request.explicitTarget().intentExpert()) {
            throw new IllegalArgumentException("聚合意图专家admission参数不完整");
        }
        IntentExpertScope requested = request.command() == null
                ? null
                : request.command().intentExpertScope();
        if (requested == null
                || !request.explicitTarget().targetId().equals(requested.expertId())) {
            throw new IllegalArgumentException("聚合意图专家范围与显式目标不一致");
        }
        UserContext user = request.user();
        ChatSession session = request.session();
        ChatSession current = sessionService.lockAndReloadForMessageMutation(
                user.tenantId(), user.ownerUserId(), session);
        IntentExpertScope previous = IntentExpertContext.fromSessionMetadata(
                current.metadataJson()).orElse(null);
        boolean identityChanged = previous == null || !previous.sameIdentity(requested);
        boolean scopeChanged = !requested.equals(previous);
        AdmissionResult admission = commitRun(new RunAdmissionContext(
                user, request.command(), current, request.runId(), request.attachments(),
                requested, identityChanged));
        interactionService.cancelOpenBySessionAndCount(user, current.id());
        List<AdmissionCancellation> cancellations = identityChanged
                ? runtimeBindingService.cancelActiveForAdmissionWithSnapshots(
                        user.tenantId(), user.ownerUserId(), current.id())
                : List.of();
        if (scopeChanged) {
            sessionService.updateMetadataWithoutTouch(current,
                    IntentExpertContext.replaceSessionMetadata(current.metadataJson(), requested));
        }
        return new AdmissionResult(admission.messagePlan(), admission.run(), cancellations, List.of(),
                requested, identityChanged);
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
        if (explicitTarget.intentExpert()) {
            throw new IllegalArgumentException("INTENT_EXPERT必须使用聚合意图专家admission");
        }
        ChatSession currentSession = sessionService.lockAndReloadForMessageMutation(
                user.tenantId(), user.ownerUserId(), session);
        IntentExpertScope previousScope = IntentExpertContext.fromSessionMetadata(
                currentSession.metadataJson()).orElse(null);
        AdmissionResult admission = commitRun(new RunAdmissionContext(
                user, command, currentSession, runId, attachments, null, false));
        int cancelledInteractions = interactionService.cancelOpenBySessionAndCount(user, session.id());
        boolean deferDomainAgentBinding = explicitTarget.domainAgent() && !attachments.isEmpty();
        List<AdmissionCancellation> bindingCancellations = deferDomainAgentBinding && previousScope == null
                ? List.of()
                : explicitTarget.domainExpert() && cancelledInteractions == 0
                        ? runtimeBindingService.cancelActiveForAdmissionExceptPinnedDomainExpertWithSnapshots(
                                user.tenantId(), user.ownerUserId(), session.id(), explicitTarget.targetId())
                        : runtimeBindingService.cancelActiveForAdmissionWithSnapshots(
                                user.tenantId(), user.ownerUserId(), session.id());
        if (previousScope != null) {
            sessionService.updateMetadataWithoutTouch(currentSession,
                    IntentExpertContext.replaceSessionMetadata(currentSession.metadataJson(), null));
        }
        List<AdmissionCancellation> restorableCancellations = previousScope == null
                ? bindingCancellations
                : List.of();
        return new AdmissionResult(admission.messagePlan(), admission.run(), bindingCancellations,
                restorableCancellations, null, false);
    }

    /** 兼容现有内部调用与事务契约测试。 */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitDirectDomainAgent(UserContext user, ChatCommand command, ChatSession session,
                                                   String runId, List<AttachmentRef> attachments) {
        return commitDirectRuntime(new DirectRuntimeAdmissionCommand(
                user, command, session, runId, attachments,
                new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_AGENT, command.targetId())));
    }

    private AdmissionResult commitRun(RunAdmissionContext context) {
        UserContext user = context.user();
        ChatCommand command = context.command();
        ChatSession session = context.session();
        ChatRunMessagePlan messagePlan = sessionService.prepareRunMessage(
                user, command, session, context.runId(), context.attachments());
        ChatRun run = chatRunService.insertRunning(new CreateChatRunContext(
                context.runId(),
                user,
                session.id(),
                null,
                null,
                IntentExpertContext.withScope(
                        SelectedIntentContext.removeReserved(command.metadata()), context.intentExpertScope()),
                messagePlan.runMode(),
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()
        ));
        return new AdmissionResult(messagePlan, run, List.of(), List.of(), context.intentExpertScope(),
                context.emitIntentExpertSelection());
    }

    /** 原子受理候选DomainAgent切换：复用source user消息并替换冲突Binding。 */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commitCandidateSwitch(CandidateSwitchAdmissionCommand request) {
        if (request == null || request.source() == null) {
            throw new IllegalArgumentException("候选技能切换admission参数不能为空");
        }
        UserContext user = request.user();
        ChatSession session = request.source().session();
        ChatSession currentSession = sessionService.lockAndReloadForMessageMutation(
                user.tenantId(), user.ownerUserId(), session);
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
                    IntentExpertContext.removeReserved(
                            SelectedIntentContext.removeReserved(request.command().metadata())),
                    messagePlan.runMode(),
                    messagePlan.parentMessageId(),
                    messagePlan.userMessage().id()));
        } catch (ActiveRunExistsException ex) {
            throw CandidateSwitchConflictException.staleSource(request.source().sourceRunId());
        }
        interactionService.cancelOpenBySessionAndCount(user, currentSession.id());
        IntentExpertScope previousScope = IntentExpertContext.fromSessionMetadata(
                currentSession.metadataJson()).orElse(null);
        List<AttachmentRef> requestedAttachments = request.command().attachments() == null
                ? List.of()
                : request.command().attachments();
        List<AdmissionCancellation> bindingCancellations = requestedAttachments.isEmpty()
                || previousScope != null
                ? runtimeBindingService.cancelActiveForAdmissionWithSnapshots(
                        user.tenantId(), user.ownerUserId(), currentSession.id())
                : List.of();
        if (previousScope != null) {
            sessionService.updateMetadataWithoutTouch(currentSession,
                    IntentExpertContext.replaceSessionMetadata(currentSession.metadataJson(), null));
        }
        List<AdmissionCancellation> restorableCancellations = previousScope == null
                ? bindingCancellations
                : List.of();
        return new AdmissionResult(messagePlan, run, bindingCancellations,
                restorableCancellations, null, false);
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
        ChatSession currentSession = sessionService.lockAndReloadForMessageMutation(
                user.tenantId(), user.ownerUserId(), session);
        IntentExpertScope intentExpertScope = IntentExpertContext.fromSessionMetadata(
                currentSession.metadataJson()).orElse(null);
        ChatRunMessagePlan messagePlan = sessionService.prepareIntentClarificationAnswer(
                user, currentSession, runId, interaction.assistantMessageId(),
                command.answerText(), command.attachments());
        ChatRun run = chatRunService.insertInteractionRunning(new CreateChatRunContext(
                runId,
                user,
                currentSession.id(),
                null,
                null,
                IntentExpertContext.withScope(command.runMetadata(), intentExpertScope),
                ChatRunMode.NEXT,
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()
        ), interaction.id());
        int answered = interactionService.markAnsweredForRun(interaction, runId);
        if (answered != 1) {
            throw new IllegalStateException("意图澄清 Interaction 已不再由当前 continuation run 持有");
        }
        return new AdmissionResult(messagePlan, run, List.of(), List.of(), intentExpertScope, false);
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
        ChatSession currentSession = sessionService.lockAndReloadForMessageMutation(
                user.tenantId(), user.ownerUserId(), session);
        IntentExpertScope intentExpertScope = IntentExpertContext.fromSessionMetadata(
                currentSession.metadataJson()).orElse(null);
        ChatRunMessagePlan messagePlan = sessionService.prepareReusableIntentClarification(
                user,
                currentSession,
                runId,
                interaction,
                command.attachments());
        ChatRun run = chatRunService.insertInteractionRunning(new CreateChatRunContext(
                runId,
                user,
                currentSession.id(),
                null,
                null,
                IntentExpertContext.withScope(command.runMetadata(), intentExpertScope),
                ChatRunMode.NEXT,
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()
        ), interaction.id());
        return new AdmissionResult(messagePlan, run, List.of(), List.of(), intentExpertScope, false);
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

    private record RunAdmissionContext(
            UserContext user,
            ChatCommand command,
            ChatSession session,
            String runId,
            List<AttachmentRef> attachments,
            IntentExpertScope intentExpertScope,
            boolean emitIntentExpertSelection
    ) {
        private RunAdmissionContext {
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
                                  List<AdmissionCancellation> cancelledBindingsForCacheSync,
                                  List<AdmissionCancellation> restorableAdmissionCancellations,
                                  IntentExpertScope intentExpertScope,
                                  boolean emitIntentExpertSelection) {
        public AdmissionResult {
            cancelledBindingsForCacheSync = cancelledBindingsForCacheSync == null
                    ? List.of()
                    : List.copyOf(cancelledBindingsForCacheSync);
            restorableAdmissionCancellations = restorableAdmissionCancellations == null
                    ? List.of()
                    : List.copyOf(restorableAdmissionCancellations);
        }

        public AdmissionResult(ChatRunMessagePlan messagePlan, ChatRun run) {
            this(messagePlan, run, List.of(), List.of(), null, false);
        }

        public AdmissionResult(ChatRunMessagePlan messagePlan, ChatRun run,
                               List<AdmissionCancellation> bindingCancellations) {
            this(messagePlan, run, bindingCancellations, bindingCancellations, null, false);
        }

        public List<RuntimeBinding> cancelledBindings() {
            return cancelledBindingsForCacheSync.stream().map(AdmissionCancellation::cancelled).toList();
        }
    }
}
