package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.ChatMessageRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import org.springframework.beans.factory.ObjectProvider;

/** Test-only assembly for collaborators that production Spring injects directly. */
final class TestSessionApplicationServices {
    private TestSessionApplicationServices() {
    }

    static SessionApplicationService create(
            SessionRepository sessions,
            ChatMessageRepository messages,
            IdGenerator ids,
            PermissionChecker permissionChecker) {
        return create(sessions, messages, ids, permissionChecker, null, null, null, null);
    }

    static SessionApplicationService create(
            SessionRepository sessions,
            ChatMessageRepository messages,
            IdGenerator ids,
            PermissionChecker permissionChecker,
            ChatRunApplicationService chatRuns,
            RuntimeBindingService bindings,
            ChatSessionLifecycleService shareLifecycle,
            ObjectProvider<ChatRunStopCoordinator> stopCoordinators) {
        SessionDeleteRunSupport deleteSupport = new SessionDeleteRunSupport(
                chatRuns, bindings, shareLifecycle, stopCoordinators, null);
        return new SessionApplicationService(sessions, messages, ids, permissionChecker, deleteSupport);
    }
}
