package com.huawei.it.ex.one.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.runtime.application.service.RuntimeRecoveryService;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeRecoveryRequest;
import com.huawei.it.ex.one.security.application.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.security.application.auth.SgovTokenResolver;
import com.huawei.it.ex.one.common.instance.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.intent.application.client.IntentRetryPolicy;
import com.huawei.it.ex.one.common.trace.TraceContextProvider;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.security.infrastructure.auth.DefaultSgovTokenResolver;
import com.huawei.it.ex.one.common.id.infrastructure.GeneratedApplicationInstanceIdProvider;
import com.huawei.it.ex.one.intent.infrastructure.client.DefaultIntentRetryPolicy;
import com.huawei.it.ex.one.runtime.infrastructure.UnsupportedAgentRuntimeRecoveryPort;
import com.huawei.it.ex.one.common.trace.jalor.JalorTraceContextProvider;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

class DefaultPortImplementationConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DefaultPortImplementationConfiguration.class);

    @Test
    void createsDefaultPortImplementationsWhenNoCustomBeanExists() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ApplicationInstanceIdProvider.class);
            assertThat(context.getBean(ApplicationInstanceIdProvider.class))
                    .isInstanceOf(GeneratedApplicationInstanceIdProvider.class);
            assertThat(context).hasSingleBean(RuntimeRecoveryService.class);
            assertThat(context.getBean(RuntimeRecoveryService.class))
                    .isInstanceOf(UnsupportedAgentRuntimeRecoveryPort.class);
            assertThat(context).hasSingleBean(SgovTokenResolver.class);
            assertThat(context.getBean(SgovTokenResolver.class))
                    .isInstanceOf(DefaultSgovTokenResolver.class);
            assertThat(context).hasSingleBean(IntentRetryPolicy.class);
            assertThat(context.getBean(IntentRetryPolicy.class))
                    .isInstanceOf(DefaultIntentRetryPolicy.class);
            assertThat(context).hasSingleBean(TraceContextProvider.class);
            assertThat(context.getBean(TraceContextProvider.class))
                    .isInstanceOf(JalorTraceContextProvider.class);
            assertThat(context.getBean(TraceContextProvider.class).resolve()).isEqualTo(TraceContext.empty());
        });
    }

    @Test
    void defaultInstanceIdProviderUsesConfiguredInstanceId() {
        contextRunner
                .withPropertyValues("financeex.instance-id=configured-instance")
                .run(context -> assertThat(context.getBean(ApplicationInstanceIdProvider.class).currentInstanceId())
                        .isEqualTo("configured-instance"));
    }

    @Test
    void customInstanceIdProviderOverridesDefaultPortImplementation() {
        ApplicationInstanceIdProvider custom = () -> "custom-instance";

        contextRunner
                .withBean(ApplicationInstanceIdProvider.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(ApplicationInstanceIdProvider.class);
                    assertThat(context.getBean(ApplicationInstanceIdProvider.class)).isSameAs(custom);
                });
    }

    @Test
    void customRuntimeRecoveryPortOverridesDefaultPortImplementation() {
        RuntimeRecoveryService custom = new RuntimeRecoveryService() {
            @Override
            public boolean supports(AgentRuntimeRecoveryRequest request) {
                return true;
            }

            @Override
            public Flux<ChatEvent> recover(AgentRuntimeRecoveryRequest request) {
                return Flux.empty();
            }
        };

        contextRunner
                .withBean(RuntimeRecoveryService.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRecoveryService.class);
                    assertThat(context.getBean(RuntimeRecoveryService.class)).isSameAs(custom);
                });
    }

    @Test
    void customSgovTokenResolverOverridesDefaultPortImplementation() {
        SgovTokenResolver custom = new SgovTokenResolver() {
            @Override
            public Optional<String> resolve(AuthHeaderRequest request, String appId, String secret) {
                return Optional.of("custom-token");
            }
        };

        contextRunner
                .withBean(SgovTokenResolver.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(SgovTokenResolver.class);
                    assertThat(context.getBean(SgovTokenResolver.class)).isSameAs(custom);
                });
    }

    @Test
    void customIntentRetryPolicyOverridesDefaultPortImplementation() {
        IntentRetryPolicy custom = context -> false;

        contextRunner
                .withBean(IntentRetryPolicy.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(IntentRetryPolicy.class);
                    assertThat(context.getBean(IntentRetryPolicy.class)).isSameAs(custom);
                });
    }

    @Test
    void customTraceContextProviderOverridesDefaultPortImplementation() {
        TraceContextProvider custom = () -> new TraceContext("jalor-trace-1");

        contextRunner
                .withBean(TraceContextProvider.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(TraceContextProvider.class);
                    assertThat(context.getBean(TraceContextProvider.class)).isSameAs(custom);
                });
    }
}
