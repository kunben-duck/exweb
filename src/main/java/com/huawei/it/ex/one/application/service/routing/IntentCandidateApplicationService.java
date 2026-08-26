package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.application.config.IntentCandidateProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidate;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateProvider;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateQueryException;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;

/** 校验Chat消息归属后查询Intent候选技能。 */
@Service
public class IntentCandidateApplicationService {
    private static final AppLogger log = AppLoggerFactory.getLogger(IntentCandidateApplicationService.class);
    private static final int MAX_MESSAGE_ID_LENGTH = 64;

    private final ChatMessageRepository messageRepository;
    private final IntentCandidateProvider candidateProvider;
    private final Semaphore permits;
    private final int maxConcurrency;

    public IntentCandidateApplicationService(ChatMessageRepository messageRepository,
                                             IntentCandidateProvider candidateProvider,
                                             IntentCandidateProperties properties) {
        this.messageRepository = messageRepository;
        this.candidateProvider = candidateProvider;
        this.maxConcurrency = properties.getMaxConcurrency();
        this.permits = new Semaphore(maxConcurrency);
    }

    public Mono<List<IntentCandidate>> findCandidates(UserContext user, String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        return Mono.defer(() -> {
            if (!permits.tryAcquire()) {
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RESOURCE_EXHAUSTED,
                                "Intent candidate query concurrency limit reached")
                        .operation("intent.candidates")
                        .attribute("maxConcurrency", maxConcurrency)
                        .build());
                return Mono.error(IntentCandidateQueryException.busy());
            }
            return Mono.fromCallable(() -> requireOwnedUserMessage(user, normalizedMessageId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(message -> candidateProvider.findCandidates(user, message.id()))
                    .doFinally(ignored -> permits.release());
        });
    }

    private ChatMessage requireOwnedUserMessage(UserContext user, String messageId) {
        ChatMessage message = messageRepository
                .findByOwnerAndId(user.tenantId(), user.ownerUserId(), messageId)
                .orElseThrow(() -> new SecurityException("消息不存在或不属于当前用户"));
        if (!"user".equals(message.role())) {
            throw new IllegalArgumentException("messageId必须指向user消息");
        }
        return message;
    }

    private String normalizeMessageId(String messageId) {
        String normalized = messageId == null || messageId.isBlank() ? null : messageId.trim();
        if (normalized == null
                || "null".equalsIgnoreCase(normalized)
                || "undefined".equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("messageId不能为空");
        }
        if (normalized.length() > MAX_MESSAGE_ID_LENGTH) {
            throw new IllegalArgumentException("messageId长度不能超过64");
        }
        return normalized;
    }
}
