package com.huawei.finance.front.one.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FinanceExRedisKeyNamespaceTest {
    @Test
    void injectsFirstActiveProfileIntoFinExPrefix() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "blue");

        FinanceExRedisKeyNamespace namespace = new FinanceExRedisKeyNamespace(environment);

        assertThat(namespace.env()).isEqualTo("dev");
        assertThat(namespace.prefix("fin_ex:chat_run:active"))
                .isEqualTo("fin_ex:dev:chat_run:active");
    }

    @Test
    void usesDefaultEnvWhenNoActiveProfileExists() {
        FinanceExRedisKeyNamespace namespace = new FinanceExRedisKeyNamespace(new MockEnvironment());

        assertThat(namespace.env()).isEqualTo("default");
        assertThat(namespace.prefix("fin_ex:chat_stream"))
                .isEqualTo("fin_ex:default:chat_stream");
    }

    @Test
    void normalizesUnsafeProfileCharacters() {
        assertThat(FinanceExRedisKeyNamespace.ofEnv("Prod Blue").env()).isEqualTo("prod_blue");
        assertThat(FinanceExRedisKeyNamespace.ofEnv("prod:blue").env()).isEqualTo("prod_blue");
        assertThat(FinanceExRedisKeyNamespace.ofEnv(" ! ").env()).isEqualTo("default");
    }

    @Test
    void rejectsNonFinExLogicalPrefix() {
        FinanceExRedisKeyNamespace namespace = FinanceExRedisKeyNamespace.ofEnv("dev");

        assertThatThrownBy(() -> namespace.prefix("other:chat_run"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须以 fin_ex 开头");
    }
}
