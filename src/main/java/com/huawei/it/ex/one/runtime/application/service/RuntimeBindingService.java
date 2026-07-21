package com.huawei.it.ex.one.runtime.application.service;

import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentBindingCommand;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingResolution;
import com.huawei.it.ex.one.runtime.application.model.RuntimeInteractionBindingRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Application boundary for RuntimeBinding queries and lifecycle changes. */
public interface RuntimeBindingService {

    Optional<RuntimeBinding> findActive(
            String tenantId, String userId, String sessionId, String leafMessageId);

    RuntimeBindingResolution resolveForRun(
            String tenantId, String userId, String sessionId, String runId, String leafMessageId);

    Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId);

    Optional<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId);

    Optional<RuntimeBinding> findActiveDomainAgentBySession(String tenantId, String userId, String sessionId);

    RuntimeBinding loadDomainAgentForReroute(
            String tenantId, String userId, String sessionId, String bindingId, String expectedDomainAgentId);

    RuntimeBinding create(
            String tenantId, String userId, String sessionId, String runId, String leafMessageId);

    RuntimeBinding create(String tenantId, String userId, String sessionId, String runId);

    RuntimeBinding bindDomainAgentForRun(DomainAgentBindingCommand command);

    RuntimeBinding touchDomainAgentForRun(
            RuntimeBinding binding, String runId, String domainAgentId, String routeSource,
            Map<String, Object> intentMetadata);

    RuntimeBinding touchForRun(RuntimeBinding binding, String runId);

    RuntimeBinding resumeForInteraction(RuntimeInteractionBindingRequest request, String runId);

    RuntimeBinding touchAndMoveToLeaf(RuntimeBinding binding, String runId, String leafMessageId);

    RuntimeBinding completeAfterRun(RuntimeBinding binding, String runId, String leafMessageId);

    RuntimeBinding moveToLeaf(RuntimeBinding binding, String leafMessageId);

    void cancelActive(String tenantId, String userId, String sessionId);

    List<RuntimeBinding> cancelActiveForAdmission(String tenantId, String userId, String sessionId);

    void cancelAllForSession(String tenantId, String userId, String sessionId);

    RuntimeBinding markNotRoutable(RuntimeBinding binding, String rejectCode);

    RuntimeBinding cancelForRefusalInCurrentTransaction(RuntimeBinding binding, String rejectCode);

    RuntimeBinding refreshInCurrentTransaction(RuntimeBinding binding, String runId, String leafMessageId);

    RuntimeBinding completeInCurrentTransaction(RuntimeBinding binding, String runId, String leafMessageId);

    RuntimeBinding observeEventInCurrentTransaction(RuntimeBinding binding, ChatEvent event);

    RuntimeBinding invalidateUnavailableInCurrentTransaction(RuntimeBinding binding, ChatEvent event);

    void synchronizeCache(RuntimeBinding binding);

    RuntimeBinding observeEvent(RuntimeBinding binding, ChatEvent event);
}
