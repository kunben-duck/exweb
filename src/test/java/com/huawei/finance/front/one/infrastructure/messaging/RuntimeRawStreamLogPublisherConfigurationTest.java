package com.huawei.finance.front.one.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RuntimeRawStreamLogPublisherConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RuntimeRawStreamLogPublisherConfiguration.class);

    @Test
    void createsNoopPublisherByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RuntimeRawStreamLogPublisher.class);
            assertThat(context.getBean(RuntimeRawStreamLogPublisher.class))
                    .isInstanceOf(NoopRuntimeRawStreamLogPublisher.class);
        });
    }

    @Test
    void customPublisherOverridesDefaultSelection() {
        RuntimeRawStreamLogPublisher custom = chunk -> {
        };
        contextRunner
                .withBean(RuntimeRawStreamLogPublisher.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRawStreamLogPublisher.class);
                    assertThat(context.getBean(RuntimeRawStreamLogPublisher.class)).isSameAs(custom);
                });
    }
}
