/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.routing.RuntimeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

class RuntimeBindingCompletionPolicyTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " , , "})
    void emptyConfigurationPreservesExpertBehavior(String config) {
        assertThat(new RuntimeBindingCompletionPolicy(config).retainBindingOnCompletion(expert("analysis")))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({"analysis,false", "tax,false", "Analysis,true", "analysis-v2,true", "EX_RE_analysis,true"})
    void onlyExactFinalRoleNamesMatch(String role, boolean keepActive) {
        RuntimeBindingCompletionPolicy policy = new RuntimeBindingCompletionPolicy(" analysis, ,tax,analysis, ");
        assertThat(policy.retainBindingOnCompletion(expert(role))).isEqualTo(keepActive);
    }

    @Test
    void providerAndProfileKeepTheirOriginalMeaning() {
        RuntimeBindingCompletionPolicy policy = new RuntimeBindingCompletionPolicy("analysis");
        RuntimeBinding expert = expert("analysis");
        assertThat(policy.retainBindingOnCompletion(null)).isFalse();
        assertThat(policy.retainBindingOnCompletion(binding("domain-agent", expert.metadata()))).isTrue();
        assertThat(policy.retainBindingOnCompletion(binding("other", expert.metadata()))).isFalse();
        assertThat(policy.retainBindingOnCompletion(binding("relay", Map.of()))).isFalse();
        assertThat(policy.retainBindingOnCompletion(binding("relay", Map.of(
                RuntimeProfileMetadata.PROFILE_KEY, "DELEGATE",
                RuntimeProfileMetadata.ROLE_NAME_KEY, "analysis")))).isFalse();
        assertThat(policy.retainBindingOnCompletion(binding("relay", Map.of(
                RuntimeProfileMetadata.PROFILE_KEY, "DOMAIN_EXPERT")))).isFalse();
        assertThat(new RuntimeBindingCompletionPolicy("*").retainBindingOnCompletion(expert)).isTrue();
        assertThat(policy.retainBindingOnCompletion(expert.withStatus(RuntimeBindingStatus.CANCELLED))).isTrue();
        assertThat(policy.retainBindingOnCompletion(expert.withStatus(RuntimeBindingStatus.RESUMABLE))).isTrue();
    }

    @Test
    void springResolvesOptionalConfigurationOnceAtConstruction() {
        new ApplicationContextRunner().withBean(RuntimeBindingCompletionPolicy.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RuntimeBindingCompletionPolicy.class)
                    .retainBindingOnCompletion(expert("analysis"))).isTrue();
        });
        new ApplicationContextRunner().withBean(RuntimeBindingCompletionPolicy.class)
                .withPropertyValues("financeex.agent-runtime.relay.domain-expert.non-sticky-role-names=analysis,tax")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RuntimeBindingCompletionPolicy policy = context.getBean(RuntimeBindingCompletionPolicy.class);
                    assertThat(policy.retainBindingOnCompletion(expert("analysis"))).isFalse();
                    assertThat(policy.retainBindingOnCompletion(expert("tax"))).isFalse();
                    assertThat(policy.retainBindingOnCompletion(expert("other"))).isTrue();
                });
    }

    private RuntimeBinding expert(String role) {
        return binding("relay", RuntimeProfileMetadata.bindingMetadata(
                RuntimeProfile.DOMAIN_EXPERT, "delegate", "domain_expert", role));
    }

    private RuntimeBinding binding(String provider, Map<String, Object> metadata) {
        Instant now = Instant.now();
        return new RuntimeBinding("binding", "tenant", "user", "session", provider, "leaf", "relay-session",
                RuntimeBindingStatus.ACTIVE, "run", null, now, now, metadata);
    }
}
