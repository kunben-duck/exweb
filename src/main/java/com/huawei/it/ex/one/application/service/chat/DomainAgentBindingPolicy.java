package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

/** Applies the existing DomainAgent refusal binding state rules. */
final class DomainAgentBindingPolicy {
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final DomainAgentProperties domainAgentProperties;

    DomainAgentBindingPolicy(RuntimeBindingApplicationService runtimeBindingService,
                             DomainAgentProperties domainAgentProperties) {
        this.runtimeBindingService = runtimeBindingService;
        this.domainAgentProperties = domainAgentProperties;
    }

    RuntimeBinding markRejectedAutomatic(RuntimeBinding binding, DomainAgentRefusal refusal) {
        if (binding == null || requiresRouteSwitchConfirmation(routeSource(binding))) {
            return binding;
        }
        return markRejected(binding, refusal);
    }

    RuntimeBinding markRejected(RuntimeBinding binding, DomainAgentRefusal refusal) {
        if (binding == null || binding.status() != RuntimeBindingStatus.ACTIVE) {
            return binding;
        }
        return runtimeBindingService.markNotRoutable(binding, refusal == null ? null : refusal.code());
    }

    boolean protectedRouteSource(String source) {
        return "front-selected".equals(source) || "user-confirmed".equals(source);
    }

    boolean requiresRouteSwitchConfirmation(String source) {
        return protectedRouteSource(source) && !domainAgentProperties.isRefusalAutoSwitchEnabled();
    }

    String routeSource(RuntimeBinding binding) {
        if (binding == null || binding.metadata() == null) {
            return null;
        }
        Object value = binding.metadata().get("routeSource");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    String runtimeBindingLeafId(ChatCommand command) {
        return command == null ? null : command.parentMessageId();
    }
}
