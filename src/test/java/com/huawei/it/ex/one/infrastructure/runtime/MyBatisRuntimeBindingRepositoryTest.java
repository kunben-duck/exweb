package com.huawei.it.ex.one.infrastructure.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

class MyBatisRuntimeBindingRepositoryTest {

    @Test
    void bindingCompensationUsesBoundedTransactionTimeout() throws NoSuchMethodException {
        Method method = MyBatisRuntimeBindingRepository.class.getMethod(
                "cancelActiveForRun", String.class, String.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString()).isEqualTo(
                "${financeex.domain-agent.binding-compensation-transaction-timeout-seconds:2}");
    }
}
