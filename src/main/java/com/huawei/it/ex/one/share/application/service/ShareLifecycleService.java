package com.huawei.it.ex.one.share.application.service;

import com.huawei.it.ex.one.chat.application.service.ChatSessionLifecycleService;
import com.huawei.it.ex.one.share.application.repository.ChatShareRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Share lifecycle participant invoked when a chat session is deleted. */
@Service
public class ShareLifecycleService implements ChatSessionLifecycleService {
    private final ChatShareRepository shareRepository;

    public ShareLifecycleService(ChatShareRepository shareRepository) {
        this.shareRepository = shareRepository;
    }

    @Override
    public void revokeActiveBySession(
            String tenantId, String userId, String sessionId, Instant revokedAt) {
        shareRepository.revokeActiveBySession(tenantId, userId, sessionId, revokedAt);
    }
}
