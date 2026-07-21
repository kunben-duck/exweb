package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentRefusal;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingStatus;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;

/** Applies the existing DomainAgent refusal binding state rules. */
final class DomainAgentBindingPolicy {
    private final RuntimeBindingService runtimeBindingService;

    DomainAgentBindingPolicy(RuntimeBindingService runtimeBindingService) {
        this.runtimeBindingService = runtimeBindingService;
    }

    RuntimeBinding markRejectedAutomatic(RuntimeBinding binding, DomainAgentRefusal refusal) {
        if (binding == null || protectedRouteSource(routeSource(binding))) {
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
