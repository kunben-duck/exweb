/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.routing.DomainExpertAccessNameResolver;
import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves a candidate skill ID after the query adapter has removed the common response prefix. */
@Component
final class CandidateSwitchTargetResolver {
    private final DomainExpertAccessNameResolver expertResolver;
    private final SensitiveInformationAccessNameResolver sensitiveResolver;

    CandidateSwitchTargetResolver(
            @Value("${financeex.intent.domain-expert-access-name-prefix:}") String expertPrefix,
            SensitiveInformationAccessNameResolver sensitiveResolver) {
        this.expertResolver = new DomainExpertAccessNameResolver(expertPrefix);
        this.sensitiveResolver = sensitiveResolver;
    }

    ExplicitRuntimeTarget resolve(String skillId) {
        ExplicitRuntimeTarget domainAgent = new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_AGENT, skillId);
        if (sensitiveResolver.matches(domainAgent.targetId())) {
            return domainAgent;
        }
        DomainExpertAccessNameResolver.Resolution expert = expertResolver.resolve(domainAgent.targetId());
        if (expert.malformedDomainExpert()) {
            throw new IllegalArgumentException("候选专家技能缺少 roleName");
        }
        return expert.validDomainExpert()
                ? new ExplicitRuntimeTarget(ExplicitRuntimeTarget.Type.DOMAIN_EXPERT, expert.roleName())
                : domainAgent;
    }
}
