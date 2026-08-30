/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.intent.IntentAccessNameResolver;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceCorrectionRepository;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceUnavailableException;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionRecordRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;

/** Records user-selected Intent corrections independently from chat run admission. */
@Service
public class IntentPreferenceCorrectionApplicationService {
    private static final String TYPE_INTENT_CANDIDATE = "INTENT_CANDIDATE";
    private static final String TYPE_AMBIGUOUS_ROUTE = "AMBIGUOUS_ROUTE";
    private static final String ACTION_SELECT_CANDIDATE = "SELECT_CANDIDATE";
    private static final String SELECTION_SOURCE_USER = "USER";
    private static final int MAX_ID_LENGTH = 64;

    private final ChatMessageRepository messageRepository;
    private final ChatInteractionRequestRepository interactionRepository;
    private final IntentRecognitionRecordRepository recognitionRepository;
    private final IntentPreferenceCorrectionRepository correctionRepository;
    private final IntentAccessNameResolver accessNameResolver;
    private final IdGenerator idGenerator;
    private final Scheduler writeScheduler;
    private final Clock clock;

    @Autowired
    public IntentPreferenceCorrectionApplicationService(
            ChatMessageRepository messageRepository,
            ChatInteractionRequestRepository interactionRepository,
            IntentRecognitionRecordRepository recognitionRepository,
            IntentPreferenceCorrectionRepository correctionRepository,
            IntentAccessNameResolver accessNameResolver,
            IdGenerator idGenerator,
            @Qualifier("intentPreferenceWriteExecutor") Executor writeExecutor) {
        this(messageRepository, interactionRepository, recognitionRepository, correctionRepository,
                accessNameResolver, idGenerator, writeExecutor, Clock.systemUTC());
    }

    IntentPreferenceCorrectionApplicationService(
            ChatMessageRepository messageRepository,
            ChatInteractionRequestRepository interactionRepository,
            IntentRecognitionRecordRepository recognitionRepository,
            IntentPreferenceCorrectionRepository correctionRepository,
            IntentAccessNameResolver accessNameResolver,
            IdGenerator idGenerator,
            Executor writeExecutor,
            Clock clock) {
        this.messageRepository = messageRepository;
        this.interactionRepository = interactionRepository;
        this.recognitionRepository = recognitionRepository;
        this.correctionRepository = correctionRepository;
        this.accessNameResolver = accessNameResolver;
        this.idGenerator = idGenerator;
        this.writeScheduler = Schedulers.fromExecutor(writeExecutor == null ? Runnable::run : writeExecutor);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Mono<Void> record(UserContext user, IntentPreferenceCorrectionCommand command) {
        return Mono.fromRunnable(() -> recordRequired(user, command))
                .subscribeOn(writeScheduler)
                .onErrorMap(this::persistenceFailure,
                        failure -> new IntentPreferenceUnavailableException(
                                "意图偏好记录暂不可用，请稍后重试", failure))
                .then();
    }

    private void recordRequired(UserContext user, IntentPreferenceCorrectionCommand command) {
        requireUser(user);
        if (command == null) {
            throw new IllegalArgumentException("偏好记录请求不能为空");
        }
        String selectionType = normalizeRequired(command.selectionType(), "selectionType不能为空");
        String accessName = accessNameResolver.resolve(command.intentAccessName());
        if (accessName == null || accessName.isBlank()) {
            throw new IllegalArgumentException("intentAccessName不能为空且服务端未配置默认值");
        }
        IntentPreferenceCorrection correction = switch (selectionType) {
            case TYPE_INTENT_CANDIDATE -> fromIntentCandidate(user, command, accessName);
            case TYPE_AMBIGUOUS_ROUTE -> fromAmbiguousRoute(user, command, accessName);
            default -> throw new IllegalArgumentException(
                    "selectionType仅支持INTENT_CANDIDATE或AMBIGUOUS_ROUTE");
        };
        correctionRepository.upsert(correction);
    }

    private IntentPreferenceCorrection fromIntentCandidate(
            UserContext user, IntentPreferenceCorrectionCommand command, String accessName) {
        rejectPresent(command.interactionId(), "INTENT_CANDIDATE不能提交interactionId");
        String messageId = normalizeId(command.sourceMessageId(), "sourceMessageId不能为空");
        IntentPreferenceCorrectionCommand.SelectedIntent selected = command.selectedIntent();
        if (selected == null || selected.intentName() == null || selected.intentName().isBlank()) {
            throw new IllegalArgumentException("selectedIntent.intentName不能为空");
        }
        ChatMessage message = requireOwnedUserMessage(user, messageId);
        String originalIntent = message.runId() == null || message.runId().isBlank()
                ? null
                : recognitionRepository.findLatestRecognizedIntentName(
                        user.tenantId(), user.ownerUserId(), message.sessionId(), message.runId())
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .orElse(null);
        return correction(user, accessName, new CorrectionSource(
                message.sessionId(), message.id(), TYPE_INTENT_CANDIDATE,
                message.content(), selected.intentName().trim(), originalIntent));
    }

    private IntentPreferenceCorrection fromAmbiguousRoute(
            UserContext user, IntentPreferenceCorrectionCommand command, String accessName) {
        rejectPresent(command.sourceMessageId(), "AMBIGUOUS_ROUTE不能提交sourceMessageId");
        if (command.selectedIntent() != null) {
            throw new IllegalArgumentException("AMBIGUOUS_ROUTE不能提交selectedIntent");
        }
        String interactionId = normalizeId(command.interactionId(), "interactionId不能为空");
        ChatInteractionRequest interaction = interactionRepository
                .findByOwnerAndId(user.tenantId(), user.ownerUserId(), interactionId)
                .orElseThrow(() -> new SecurityException("Interaction不存在或不属于当前用户"));
        validateManualAmbiguousSelection(interaction);
        ChatMessage message = requireOwnedUserMessage(user,
                normalizeId(interaction.userMessageId(), "Interaction缺少可信user消息"));
        if (!interaction.sessionId().equals(message.sessionId())) {
            throw new IllegalArgumentException("Interaction与source消息不属于同一会话");
        }
        String query = firstText(text(interaction.requestPayload(), "originalQuery"), message.content());
        String selectedIntentName = text(interaction.responsePayload(), "selectedIntentName");
        return correction(user, accessName, new CorrectionSource(
                interaction.sessionId(), message.id(), TYPE_AMBIGUOUS_ROUTE,
                query, selectedIntentName, null));
    }

    private void validateManualAmbiguousSelection(ChatInteractionRequest interaction) {
        boolean acceptedSelection = interaction.status() == ChatInteractionStatus.ANSWERED
                || (interaction.status() == ChatInteractionStatus.RESPONDING
                && interaction.continueRunId() != null
                && !interaction.continueRunId().isBlank());
        if (!acceptedSelection
                || interaction.interactionType() != ChatInteractionType.INTENT_CLARIFICATION
                || !TYPE_AMBIGUOUS_ROUTE.equals(text(interaction.requestPayload(), "clarificationType"))
                || !ACTION_SELECT_CANDIDATE.equals(text(interaction.responsePayload(), "interactionAction"))
                || !SELECTION_SOURCE_USER.equals(text(interaction.responsePayload(), "selectionSource"))
                || text(interaction.responsePayload(), "selectedIntentName") == null) {
            throw new IllegalArgumentException("仅可记录已受理的AMBIGUOUS_ROUTE人工候选选择");
        }
    }

    private IntentPreferenceCorrection correction(
            UserContext user,
            String accessName,
            CorrectionSource source) {
        Instant now = clock.instant();
        return new IntentPreferenceCorrection(
                idGenerator.newId("intent_pref", IdGenerateContext.of(
                        user.tenantId(), user.ownerUserId(), source.sessionId())),
                user.tenantId(),
                user.ownerUserId(),
                accessName,
                source.sessionId(),
                source.sourceMessageId(),
                source.sourceType(),
                source.query() == null ? "" : source.query(),
                source.preferenceIntent(),
                source.originalIntent(),
                now,
                now);
    }

    private ChatMessage requireOwnedUserMessage(UserContext user, String messageId) {
        ChatMessage message = messageRepository
                .findByOwnerAndId(user.tenantId(), user.ownerUserId(), messageId)
                .orElseThrow(() -> new SecurityException("消息不存在或不属于当前用户"));
        if (!"user".equals(message.role())) {
            throw new IllegalArgumentException("sourceMessageId必须指向user消息");
        }
        return message;
    }

    private void requireUser(UserContext user) {
        if (user == null || user.tenantId() == null || user.tenantId().isBlank()
                || user.ownerUserId() == null || user.ownerUserId().isBlank()) {
            throw new SecurityException("缺少可信用户身份");
        }
    }

    private String normalizeId(String value, String message) {
        String normalized = normalizeRequired(value, message);
        if (normalized.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(message.replace("不能为空", "长度不能超过64"));
        }
        return normalized;
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void rejectPresent(String value, String message) {
        if (value != null && !value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private String text(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean persistenceFailure(Throwable failure) {
        return !(failure instanceof IllegalArgumentException)
                && !(failure instanceof SecurityException)
                && !(failure instanceof IntentPreferenceUnavailableException);
    }

    private record CorrectionSource(
            String sessionId,
            String sourceMessageId,
            String sourceType,
            String query,
            String preferenceIntent,
            String originalIntent
    ) {
    }
}
