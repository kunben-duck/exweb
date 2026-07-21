package com.huawei.it.ex.one.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.document.application.service.DocumentApplicationService;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.chat.infrastructure.persistence.MyBatisChatEventStore;
import com.huawei.it.ex.one.chat.infrastructure.persistence.MyBatisChatRunExecutionRepository;
import com.huawei.it.ex.one.chat.infrastructure.persistence.MyBatisChatRunRepository;
import com.huawei.it.ex.one.chat.infrastructure.persistence.MyBatisSessionRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
                        com.huawei.it.ex.one.chat.domain.ChatInteractionRequest.class)
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
                        com.huawei.it.ex.one.common.event.ChatEvent.class, RunExecutionClaim.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void stopAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = MyBatisChatRunRepository.class
                .getMethod("tryMarkCancelling",
                        com.huawei.it.ex.one.chat.application.repository.ChatRunRepository.StopClaim.class)
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
