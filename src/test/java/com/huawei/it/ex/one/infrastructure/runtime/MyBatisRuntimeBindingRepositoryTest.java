package com.huawei.it.ex.one.infrastructure.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;

class MyBatisRuntimeBindingRepositoryTest {

    @Test
    void bindingCompensationUsesBoundedTransactionTimeout() throws NoSuchMethodException {
        Method method = MyBatisRuntimeBindingRepository.class.getMethod(
                "cancelActiveForRun", String.class, String.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(
                "${financeex.domain-agent.binding-compensation-transaction-timeout-seconds:2}");

        Method restore = MyBatisRuntimeBindingRepository.class.getMethod(
                "restoreUnstartedForRun", RuntimeBinding.class, String.class);
        assertThat(restore.getAnnotation(Transactional.class).timeoutString()).isEqualTo(
                "${financeex.domain-agent.binding-compensation-transaction-timeout-seconds:2}");
    }

    @Test
    void interactionResumeAndRestoreUseBoundedTransactionTimeout() throws NoSuchMethodException {
        Method resume = MyBatisRuntimeBindingRepository.class.getMethod(
                "resumeInteractionWithExecutionGuard",
                RuntimeBinding.class,
                String.class,
                RunExecutionClaim.class);
        Method restore = MyBatisRuntimeBindingRepository.class.getMethod(
                "restoreInteractionResume",
                String.class,
                String.class,
                String.class);

        assertThat(resume.getAnnotation(Transactional.class).timeoutString())
                .isEqualTo("${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}");
        assertThat(restore.getAnnotation(Transactional.class).timeoutString())
                .isEqualTo("${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}");
        assertThat(resume.getReturnType()).isEqualTo(Optional.class);
    }

    @Test
    void waitingInteractionCancellationUsesBoundedTransactionTimeout() throws NoSuchMethodException {
        Method cancel = MyBatisRuntimeBindingRepository.class.getMethod(
                "cancelActiveForInteraction",
                RuntimeBinding.class,
                String.class,
                String.class);

        Transactional transactional = cancel.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}");
    }
}
