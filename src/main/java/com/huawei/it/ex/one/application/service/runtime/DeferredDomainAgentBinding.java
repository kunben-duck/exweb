package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

/** DomainAgent binding candidate kept in memory until Runtime dispatch or business completion commits it. */
public record DeferredDomainAgentBinding(
        RuntimeBinding candidate,
        RuntimeBinding previousBinding
) {
    public DeferredDomainAgentBinding {
        if (candidate == null
                || !RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(candidate.provider())) {
            throw new IllegalArgumentException("Deferred DomainAgent binding candidate is invalid");
        }
        if (previousBinding != null && !candidate.id().equals(previousBinding.id())) {
            throw new IllegalArgumentException("Reused DomainAgent binding must keep its binding ID");
        }
    }

    public boolean reusesExistingBinding() {
        return previousBinding != null;
    }
}
