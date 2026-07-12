package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import com.huawei.finance.front.one.infrastructure.persistence.MyBatisChatEventStore;
import com.huawei.finance.front.one.infrastructure.persistence.MyBatisChatRunExecutionRepository;
import com.huawei.finance.front.one.infrastructure.persistence.MyBatisChatRunRepository;
import com.huawei.finance.front.one.infrastructure.session.MyBatisSessionRepository;
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
    void intentClarificationAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = ChatRunAdmissionCommitService.class
                .getMethod("commitIntentClarification",
                        ChatRunAdmissionCommitService.IntentClarificationAdmissionCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void eventAppendGateUsesBoundedTransaction() throws Exception {
        Transactional transactional = MyBatisChatEventStore.class
                .getMethod("appendWithExecutionGuard",
                        com.huawei.finance.front.one.domain.chat.ChatEvent.class, RunExecutionClaim.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(TIMEOUT);
    }

    @Test
    void stopAdmissionUsesBoundedTransaction() throws Exception {
        Transactional transactional = MyBatisChatRunRepository.class
                .getMethod("tryMarkCancelling",
                        com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository.StopClaim.class)
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
}
