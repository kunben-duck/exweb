/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.intent.IntentAccessNameResolver;
import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackException;
import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackRepository;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceCorrectionRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageInput;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;
import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Independent short transaction; never locks or writes a Run, Session or Binding. */
@Service
public class IntentFeedbackCommitService {
    private final ChatRunRepository runs;
    private final ChatMessageRepository messages;
    private final IntentFeedbackRepository feedbacks;
    private final IntentPreferenceCorrectionRepository preferences;
    private final IntentAccessNameResolver accessNames;
    private final IdGenerator ids;

    public IntentFeedbackCommitService(ChatRunRepository runs, ChatMessageRepository messages,
            IntentFeedbackRepository feedbacks, IntentPreferenceCorrectionRepository preferences,
            IntentAccessNameResolver accessNames, IdGenerator ids) {
        this.runs = runs;
        this.messages = messages;
        this.feedbacks = feedbacks;
        this.preferences = preferences;
        this.accessNames = accessNames;
        this.ids = ids;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public IntentFeedback record(UserContext user, String runId, IntentFeedbackCommand command) {
        ChatRun source = ownedRun(user, runId);
        String entry = IntentExpertContext.fromMetadata(source.metadata())
                .map(scope -> scope.intentAccessName()).orElse(command.intentAccessName());
        entry = IntentFeedbackCommand.required(accessNames.resolve(entry), 128, "intentAccessName");
        String messageId = IntentFeedbackCommand.required(source.userMessageId(), 64, "source user message");
        IntentFeedbackCommand.SelectedIntent selected = command.selectedIntent();
        Instant now = Instant.now();
        IntentFeedback submission = new IntentFeedback(
                ids.newId("intent_fb", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), source.sessionId())),
                user.tenantId(), user.ownerUserId(), source.sessionId(), source.id(), messageId, entry,
                command.feedbackType(), command.commentText(), command.replacementRunId(), command.skillId(),
                selected == null ? null : selected.intentId(), selected == null ? null : selected.intentName(), now);
        Optional<IntentFeedback> existing = feedbacks.findByOwnerAndRun(
                user.tenantId(), user.ownerUserId(), source.id());
        if (existing.isPresent()) {
            if (!existing.get().sameSubmission(submission)) {
                throw IntentFeedbackException.conflict();
            }
            return existing.get();
        }
        if (command.replacementRunId() != null) {
            ChatRun replacement = ownedRun(user, command.replacementRunId());
            if (replacement.id().equals(source.id())
                    || !Objects.equals(replacement.sessionId(), source.sessionId())
                    || !Objects.equals(replacement.userMessageId(), messageId)
                    || replacement.runMode() != ChatRunMode.REGENERATE_ASSISTANT
                    || replacement.createdAt().isBefore(source.createdAt())) {
                throw new IllegalArgumentException("replacementRunId must reference a later replacement of the same question");
            }
        }
        String query = null;
        if (selected != null) {
            ChatMessageInput input = messages.findInputByOwnerAndId(user.tenantId(), user.ownerUserId(), messageId)
                    .orElseThrow(() -> new SecurityException("Source message does not belong to the current user"));
            if (!"user".equals(input.role()) || !source.sessionId().equals(input.sessionId())) {
                throw new IllegalArgumentException("Source must be a user message in the source run session");
            }
            query = input.content() == null ? "" : input.content();
        }
        // A concurrent duplicate aborts this transaction before any preference write.
        feedbacks.insert(submission);
        if (selected != null) {
            preferences.upsert(new IntentPreferenceCorrection(
                    ids.newId("intent_pref", IdGenerateContext.of(
                            user.tenantId(), user.ownerUserId(), source.sessionId())),
                    user.tenantId(), user.ownerUserId(), entry, source.sessionId(), messageId,
                    "CORRECT".equals(command.feedbackType()) ? "INTENT_FEEDBACK_CORRECT" : "INTENT_FEEDBACK_SWITCH",
                    query, selected.intentName(), null, now, now));
        }
        return submission;
    }

    @Transactional(readOnly = true, timeout = 2)
    public Optional<IntentFeedback> find(UserContext user, String runId) {
        ChatRun run = ownedRun(user, runId);
        return feedbacks.findByOwnerAndRun(user.tenantId(), user.ownerUserId(), run.id());
    }

    private ChatRun ownedRun(UserContext user, String runId) {
        if (user == null || user.tenantId() == null || user.tenantId().isBlank()
                || user.ownerUserId() == null || user.ownerUserId().isBlank()) {
            throw new SecurityException("Missing trusted user identity");
        }
        String id = IntentFeedbackCommand.required(runId, 64, "runId");
        return runs.findByTenantIdAndUserIdAndId(user.tenantId(), user.ownerUserId(), id)
                .orElseThrow(() -> new SecurityException("Run does not belong to the current user"));
    }
}
