package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.repository.RuntimeBindingCache;
import com.huawei.it.ex.one.runtime.application.repository.RuntimeBindingRepository;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingApplicationService;
import java.time.Duration;
import java.util.Optional;

/** Test-only adapter for the former terminal-service repository constructor. */
final class ChatTerminalTestFixture {
    private ChatTerminalTestFixture() {
    }

    static ChatRunTerminalCommitService service(
            ChatStreamApplicationService streamService,
            SessionApplicationService sessionService,
            ChatRunRepository runRepository,
            ChatRunLeaseApplicationService leaseService,
            RuntimeBindingRepository bindingRepository,
            ChatInteractionApplicationService interactionService,
            Duration ttl) {
        RuntimeBindingApplicationService bindingService = new RuntimeBindingApplicationService(
                bindingRepository == null ? emptyRepository() : bindingRepository,
                emptyCache(),
                (IdGenerator) (bizType, context) -> bizType + "_test",
                ttl,
                RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER);
        return new ChatRunTerminalCommitService(
                streamService, sessionService, runRepository, leaseService, bindingService, interactionService);
    }

    private static RuntimeBindingRepository emptyRepository() {
        return new RuntimeBindingRepository() {
            @Override
            public Optional<RuntimeBinding> findById(String bindingId) {
                return Optional.empty();
            }

            @Override
            public Optional<RuntimeBinding> findActive(
                    String tenantId, String userId, String sessionId, String provider) {
                return Optional.empty();
            }

            @Override
            public RuntimeBinding save(RuntimeBinding binding) {
                return binding;
            }
        };
    }

    private static RuntimeBindingCache emptyCache() {
        return new RuntimeBindingCache() {
            @Override
            public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId) {
                return Optional.empty();
            }

            @Override
            public void put(RuntimeBinding binding) {
            }

            @Override
            public void evict(String tenantId, String userId, String sessionId) {
            }
        };
    }
}
