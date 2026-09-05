/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.document.DocumentApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.infrastructure.persistence.MyBatisChatEventStore;
import com.huawei.it.ex.one.infrastructure.persistence.MyBatisChatRunExecutionRepository;
import com.huawei.it.ex.one.infrastructure.persistence.MyBatisChatRunRepository;
import com.huawei.it.ex.one.infrastructure.session.MyBatisSessionRepository;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

class ChatRunCoordinationTransactionTest {
    private static final String TIMEOUT =
            "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}";

    @Test
    void runAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = ChatRunAdmissionCommitService.class
                .getMethod("commit", UserContext.class, ChatCommand.class, ChatSession.class,
                        String.class, List.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void directDomainAgentAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = ChatRunAdmissionCommitService.class
                .getMethod("commitDirectDomainAgent", UserContext.class, ChatCommand.class, ChatSession.class,
                        String.class, List.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void intentExpertAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = ChatRunAdmissionCommitService.class
                .getMethod("commitIntentExpert",
                        ChatRunAdmissionCommitService.DirectRuntimeAdmissionCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void candidateSwitchAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = ChatRunAdmissionCommitService.class
                .getMethod("commitCandidateSwitch",
                        ChatRunAdmissionCommitService.CandidateSwitchAdmissionCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void intentClarificationAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = ChatRunAdmissionCommitService.class
                .getMethod("commitIntentClarification",
                        ChatRunAdmissionCommitService.IntentClarificationAdmissionCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void documentResolutionUsesReadOnlyBoundedTransactions() throws Exception {
        assertDocumentResolutionTimeout("resolveAttachmentsForUser");
        assertDocumentResolutionTimeout("resolveDocumentsForUser");
        assertDocumentResolutionTimeout("resolveChatAttachmentsForUser");
    }

    @Test
    void unavailableAttachmentCancellationUsesBoundedTransaction() throws Exception {
        Transactional transactional = ChatInteractionApplicationService.class
                .getMethod("cancelWaitingForUnavailableAttachment",
                        com.huawei.it.ex.one.domain.chat.ChatInteractionRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void directAdmissionBindingCancellationRequiresExistingTransaction() throws Exception {
        Transactional transactional = RuntimeBindingApplicationService.class
                .getMethod("cancelActiveForAdmission", String.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void eventAppendGateUsesBoundedTransaction() throws Exception {
        Transactional transactional = MyBatisChatEventStore.class
                .getMethod("appendWithExecutionGuard",
                        com.huawei.it.ex.one.domain.chat.ChatEvent.class, RunExecutionClaim.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void stopAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = MyBatisChatRunRepository.class
                .getMethod("tryMarkCancelling",
                        com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository.StopClaim.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void ownerTerminalCommitsUseBoundedTransactions() throws Exception {
        assertTerminalTimeout("commitCompleted", ChatRunTerminalCommitService.CompletedCommitCommand.class);
        assertTerminalTimeout("commitWaitingUser", ChatRunTerminalCommitService.WaitingUserCommitCommand.class);
        assertTerminalTimeout("commitTerminalOnly", ChatRunTerminalCommitService.TerminalOnlyCommitCommand.class);
    }

    @Test
    void interactionExecutionInitializationUsesBoundedTransaction() throws Exception {
        Transactional transactional = MyBatisChatRunExecutionRepository.class
                .getMethod("createForInteractionRun", ChatRun.class, String.class, String.class,
                        Duration.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void terminalInteractionReconciliationUsesBoundedTransaction() throws Exception {
        Transactional transactional = ChatRunTerminalCommitService.class
                .getMethod("reconcileTerminalInteraction", ChatRun.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void messageMutationPrelockRequiresExistingTransaction() throws Exception {
        Transactional transactional = MyBatisSessionRepository.class
                .getMethod("lockForMessageMutation", String.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private void assertTerminalTimeout(String methodName, Class<?> commandType) throws Exception {
        Transactional transactional = ChatRunTerminalCommitService.class
                .getMethod(methodName, commandType)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    private void assertDocumentResolutionTimeout(String methodName) throws Exception {
        Transactional transactional = DocumentApplicationService.class
                .getMethod(methodName, UserContext.class, List.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }
}
