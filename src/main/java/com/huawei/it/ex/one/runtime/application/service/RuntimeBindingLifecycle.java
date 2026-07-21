package com.huawei.it.ex.one.runtime.application.service;

import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingStatus;
import com.huawei.it.ex.one.runtime.application.model.RuntimeInteractionBindingRequest;
import com.huawei.it.ex.one.runtime.application.model.RuntimeProviders;
import com.huawei.it.ex.one.runtime.application.repository.RuntimeBindingCache;
import com.huawei.it.ex.one.runtime.application.repository.RuntimeBindingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Package-local implementation of the existing RuntimeBinding lifecycle rules. */
final class RuntimeBindingLifecycle {
    private static final String RUNTIME_SESSION_ESTABLISHED = "runtimeSessionEstablished";

    private final RuntimeBindingRepository repository;
    private final RuntimeBindingCache cache;
    private final Duration ttl;

    RuntimeBindingLifecycle(
            RuntimeBindingRepository repository,
            RuntimeBindingCache cache,
            Duration ttl) {
        this.repository = repository;
        this.cache = cache;
        this.ttl = ttl;
    }

    RuntimeBinding touchDomainAgentForRun(
            RuntimeBinding binding,
            String runId,
            String domainAgentId,
            String routeSource,
            Map<String, Object> intentMetadata) {
        if (binding == null) {
            return null;
        }
        Map<String, Object> metadata = RuntimeBindingMetadata.domainAgent(
                domainAgentId, routeSource, intentMetadata, binding.metadata());
        return save(binding.withMetadata(metadata).withRun(runId, expiresAt(binding.provider(), false)));
    }

    RuntimeBinding touchForRun(RuntimeBinding binding, String runId) {
        if (binding == null) {
            return null;
        }
        return save(binding.withRun(runId, expiresAt(binding.provider(), relaySessionEstablished(binding))));
    }

    RuntimeBinding resumeForInteraction(RuntimeInteractionBindingRequest request, String runId) {
        if (request == null) {
            throw new IllegalArgumentException("Interaction 请求不能为空");
        }
        RuntimeBinding binding = loadInteractionBinding(request);
        RuntimeBinding next = withRuntimeSessionId(binding, request.runtimeSessionId());
        return touchAndMoveToLeaf(next, runId, request.assistantMessageId());
    }

    RuntimeBinding touchAndMoveToLeaf(RuntimeBinding binding, String runId, String leafMessageId) {
        if (binding == null) {
            return null;
        }
        RuntimeBinding next = binding.withRun(runId,
                expiresAt(binding.provider(), relaySessionEstablished(binding)));
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return save(next);
    }

    RuntimeBinding completeAfterRun(RuntimeBinding binding, String runId, String leafMessageId) {
        if (binding == null) {
            return null;
        }
        if (RuntimeProviders.DOMAIN_AGENT.equals(binding.provider())) {
            return touchAndMoveToLeaf(binding, runId, leafMessageId);
        }
        RuntimeBinding next = markRelaySessionEstablished(binding, binding.runtimeSessionId())
                .withRun(runId, null);
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return save(next.withStatus(RuntimeBindingStatus.RESUMABLE));
    }

    RuntimeBinding moveToLeaf(RuntimeBinding binding, String leafMessageId) {
        if (binding == null || leafMessageId == null || leafMessageId.isBlank()
                || leafMessageId.equals(binding.leafMessageId())) {
            return binding;
        }
        return save(binding.withLeafMessageId(leafMessageId));
    }

    void cancelActive(String tenantId, String userId, String sessionId, String runtimeProvider) {
        List<RuntimeBinding> active = repository.findActiveBySession(tenantId, userId, sessionId);
        if (active.isEmpty()) {
            active = repository.findActiveBySession(tenantId, userId, sessionId, runtimeProvider);
        }
        active.forEach(binding -> save(binding.withStatus(RuntimeBindingStatus.CANCELLED)));
        cache.evict(tenantId, userId, sessionId);
    }

    List<RuntimeBinding> cancelActiveForAdmission(String tenantId, String userId, String sessionId) {
        Map<String, RuntimeBinding> active = new LinkedHashMap<>();
        repository.findActiveBySession(tenantId, userId, sessionId)
                .forEach(binding -> active.putIfAbsent(binding.id(), binding));
        if (active.isEmpty()) {
            repository.findActiveBySession(tenantId, userId, sessionId, RuntimeProviders.RELAY)
                    .forEach(binding -> active.putIfAbsent(binding.id(), binding));
            repository.findActiveBySession(tenantId, userId, sessionId, RuntimeProviders.DOMAIN_AGENT)
                    .forEach(binding -> active.putIfAbsent(binding.id(), binding));
        }
        List<RuntimeBinding> cancelled = new ArrayList<>();
        for (RuntimeBinding binding : active.values()) {
            if (binding.status() == RuntimeBindingStatus.ACTIVE) {
                cancelled.add(repository.save(binding.withStatus(RuntimeBindingStatus.CANCELLED)));
            }
        }
        return List.copyOf(cancelled);
    }

    void cancelAllForSession(String tenantId, String userId, String sessionId) {
        Map<String, RuntimeBinding> bindings = new LinkedHashMap<>();
        repository.findActiveBySession(tenantId, userId, sessionId)
                .forEach(binding -> bindings.put(binding.id(), binding));
        repository.findResumableBySession(tenantId, userId, sessionId, RuntimeProviders.RELAY)
                .forEach(binding -> bindings.put(binding.id(), binding));
        bindings.values().forEach(binding -> save(binding.withStatus(RuntimeBindingStatus.CANCELLED)));
        cache.evict(tenantId, userId, sessionId);
    }

    RuntimeBinding markNotRoutable(RuntimeBinding binding, String rejectCode) {
        if (binding == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(binding.metadata());
        if (rejectCode != null && !rejectCode.isBlank()) {
            metadata.put("lastRejectCode", rejectCode);
        }
        return save(binding.withMetadata(metadata).withStatus(RuntimeBindingStatus.CANCELLED));
    }

    RuntimeBinding cancelForRefusalInCurrentTransaction(RuntimeBinding binding, String rejectCode) {
        if (binding == null || !RuntimeProviders.DOMAIN_AGENT.equals(binding.provider())
                || binding.status() != RuntimeBindingStatus.ACTIVE) {
            throw new IllegalStateException("DomainAgent 拒答提交缺少 ACTIVE binding");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(binding.metadata());
        if (rejectCode != null && !rejectCode.isBlank()) {
            metadata.put("lastRejectCode", rejectCode);
        }
        return repository.save(binding.withMetadata(metadata).withStatus(RuntimeBindingStatus.CANCELLED));
    }

    RuntimeBinding refreshInCurrentTransaction(RuntimeBinding binding, String runId, String leafMessageId) {
        if (binding == null || binding.status() != RuntimeBindingStatus.ACTIVE) {
            return binding;
        }
        boolean establishedRelay = RuntimeProviders.RELAY.equals(binding.provider());
        RuntimeBinding next = binding.withRun(runId, expiresAt(binding.provider(), establishedRelay));
        if (establishedRelay) {
            next = markRelaySessionEstablished(next, next.runtimeSessionId());
        }
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return repository.save(next);
    }

    RuntimeBinding completeInCurrentTransaction(RuntimeBinding binding, String runId, String leafMessageId) {
        if (binding == null) {
            return null;
        }
        if (!RuntimeProviders.DOMAIN_AGENT.equals(binding.provider())) {
            RuntimeBinding next = markRelaySessionEstablished(binding, binding.runtimeSessionId())
                    .withRun(runId, null);
            if (leafMessageId != null && !leafMessageId.isBlank()
                    && !leafMessageId.equals(next.leafMessageId())) {
                next = next.withLeafMessageId(leafMessageId);
            }
            return repository.save(next.withStatus(RuntimeBindingStatus.RESUMABLE));
        }
        return refreshInCurrentTransaction(binding, runId, leafMessageId);
    }

    RuntimeBinding observeEventInCurrentTransaction(RuntimeBinding binding, ChatEvent event) {
        if (binding == null || event == null || event.payload() == null) {
            return binding;
        }
        Object runtimeSessionId = event.payload().get("runtimeSessionId");
        if (runtimeSessionId == null || String.valueOf(runtimeSessionId).isBlank()) {
            return binding;
        }
        String nextRuntimeSessionId = String.valueOf(runtimeSessionId);
        boolean sessionIdChanged = !nextRuntimeSessionId.equals(binding.runtimeSessionId());
        boolean establishRelay = RuntimeProviders.RELAY.equals(binding.provider())
                && (!relaySessionEstablished(binding) || binding.expiresAt() != null);
        if (!sessionIdChanged && !establishRelay) {
            return binding;
        }
        RuntimeBinding next = sessionIdChanged
                ? binding.withRuntimeSessionId(nextRuntimeSessionId)
                : binding;
        return repository.save(markRelaySessionEstablished(next, nextRuntimeSessionId));
    }

    RuntimeBinding invalidateUnavailableInCurrentTransaction(RuntimeBinding binding, ChatEvent event) {
        if (binding == null || event == null || event.payload() == null
                || !"run.failed".equals(event.type())
                || !"RUNTIME_SESSION_UNAVAILABLE".equals(String.valueOf(event.payload().get("code")))) {
            return binding;
        }
        return repository.save(binding.withStatus(RuntimeBindingStatus.CANCELLED));
    }

    void synchronizeCache(RuntimeBinding binding) {
        if (binding == null) {
            return;
        }
        if (binding.status().routable()) {
            cache.put(binding);
        } else {
            cache.evict(binding.tenantId(), binding.userId(), binding.chatSessionId());
        }
    }

    RuntimeBinding observeEvent(RuntimeBinding binding, ChatEvent event) {
        if (binding == null || event == null || event.payload() == null) {
            return binding;
        }
        Object runtimeSessionId = event.payload().get("runtimeSessionId");
        if (runtimeSessionId != null && !String.valueOf(runtimeSessionId).isBlank()) {
            String nextRuntimeSessionId = String.valueOf(runtimeSessionId);
            boolean sessionIdChanged = !nextRuntimeSessionId.equals(binding.runtimeSessionId());
            boolean establishRelaySession = RuntimeProviders.RELAY.equals(binding.provider())
                    && (!relaySessionEstablished(binding) || binding.expiresAt() != null);
            if (!sessionIdChanged && !establishRelaySession) {
                return binding;
            }
            RuntimeBinding next = sessionIdChanged
                    ? binding.withRuntimeSessionId(nextRuntimeSessionId)
                    : binding;
            return save(markRelaySessionEstablished(next, nextRuntimeSessionId));
        }
        return binding;
    }

    RuntimeBinding save(RuntimeBinding binding) {
        RuntimeBinding saved = repository.save(binding);
        synchronizeCache(saved);
        return saved;
    }

    void cancelDuplicateBindings(List<RuntimeBinding> bindings, RuntimeBinding selected) {
        if (bindings == null || bindings.size() <= 1 || selected == null) {
            return;
        }
        for (RuntimeBinding binding : bindings) {
            if (!selected.id().equals(binding.id())) {
                save(binding.withStatus(RuntimeBindingStatus.CANCELLED));
            }
        }
        cache.put(selected);
    }

    RuntimeBinding activateResumableForRun(RuntimeBinding binding, String runId, String leafMessageId) {
        RuntimeBinding next = markRelaySessionEstablished(binding, binding.runtimeSessionId())
                .withRun(runId, null);
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return save(next);
    }

    Instant expiresAt(String provider, boolean relaySessionEstablished) {
        return RuntimeBindingExpirationPolicy.expiresAt(ttl,
                RuntimeProviders.RELAY.equals(normalizeProvider(provider)) && relaySessionEstablished);
    }

    private RuntimeBinding loadInteractionBinding(RuntimeInteractionBindingRequest request) {
        String bindingId = request.runtimeBindingId();
        if (bindingId == null || bindingId.isBlank()) {
            throw new IllegalStateException("Interaction 请求缺少 RuntimeBinding: " + request.id());
        }
        return repository.findById(bindingId)
                .filter(binding -> request.tenantId().equals(binding.tenantId()))
                .filter(binding -> request.userId().equals(binding.userId()))
                .filter(binding -> request.sessionId().equals(binding.chatSessionId()))
                .orElseThrow(() -> new IllegalStateException(
                        "Interaction RuntimeBinding 不存在或归属不匹配: " + bindingId));
    }

    private RuntimeBinding withRuntimeSessionId(RuntimeBinding binding, String runtimeSessionId) {
        if (binding == null || runtimeSessionId == null || runtimeSessionId.isBlank()) {
            return binding;
        }
        RuntimeBinding next = runtimeSessionId.equals(binding.runtimeSessionId())
                ? binding
                : binding.withRuntimeSessionId(runtimeSessionId);
        return markRelaySessionEstablished(next, runtimeSessionId);
    }

    private RuntimeBinding markRelaySessionEstablished(RuntimeBinding binding, String runtimeSessionId) {
        if (binding == null || !RuntimeProviders.RELAY.equals(binding.provider())) {
            return binding;
        }
        RuntimeBinding next = binding;
        if (runtimeSessionId != null && !runtimeSessionId.isBlank()
                && !runtimeSessionId.equals(next.runtimeSessionId())) {
            next = next.withRuntimeSessionId(runtimeSessionId);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(next.metadata());
        metadata.put(RUNTIME_SESSION_ESTABLISHED, true);
        return next.withMetadata(metadata).withExpiresAt(null);
    }

    private boolean relaySessionEstablished(RuntimeBinding binding) {
        return binding != null
                && RuntimeProviders.RELAY.equals(binding.provider())
                && (binding.status() == RuntimeBindingStatus.RESUMABLE
                || Boolean.TRUE.equals(binding.metadata().get(RUNTIME_SESSION_ESTABLISHED)));
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? RuntimeProviders.RELAY : provider.trim();
    }

}
