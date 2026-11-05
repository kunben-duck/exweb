/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CandidateSwitchTargetResolverTest {
    private final CandidateSwitchTargetResolver resolver = new CandidateSwitchTargetResolver(
            "RE_", new SensitiveInformationAccessNameResolver("RE_sensitive"));

    @Test
    void resolvesNormalizedCandidateAndRemovesExpertPrefixOnlyOnce() {
        assertThat(resolver.resolve(" RE_national-cfo ")).isEqualTo(
                new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_EXPERT, "national-cfo"));
        assertThat(resolver.resolve("RE_RE_national-cfo").targetId()).isEqualTo("RE_national-cfo");
        for (String skill : new String[]{"EX_RE_national-cfo", "re_national-cfo", "skill-a", "RE_sensitive"}) {
            assertThat(resolver.resolve(skill)).isEqualTo(
                    new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_AGENT, skill));
        }
    }

    @Test
    void rejectsEmptyRoleWithoutTreatingItAsDomainAgent() {
        assertThatThrownBy(() -> resolver.resolve("RE_ "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("roleName");
    }

    @Test
    void springUsesConfiguredExpertPrefix() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("financeex.intent.domain-expert-access-name-prefix=RE_")
                    .applyTo(context);
            context.registerBean(SensitiveInformationAccessNameResolver.class,
                    () -> new SensitiveInformationAccessNameResolver("sensitive"));
            context.register(CandidateSwitchTargetResolver.class);
            context.refresh();
            assertThat(context.getBean(CandidateSwitchTargetResolver.class).resolve("RE_national-cfo"))
                    .isEqualTo(new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_EXPERT, "national-cfo"));
        }
    }

    @Test
    void emptyExpertConfigurationPreservesDomainAgentBehavior() {
        CandidateSwitchTargetResolver disabled = new CandidateSwitchTargetResolver(
                "", new SensitiveInformationAccessNameResolver(""));
        assertThat(disabled.resolve("RE_analysis").domainAgent()).isTrue();
    }
}
