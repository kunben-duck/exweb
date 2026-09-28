/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentQuestionnaire;
import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅存于 Interaction request payload 的可信输入快照，不进入公开卡片或消息 Parts。 */
final class DomainAgentQuestionnaireContext {
    static final String KEY = "_domainAgentQuestionnaireContext";

    private DomainAgentQuestionnaireContext() {
    }

    static void capture(ChatEvent event, DomainAgentRunContext context) {
        if (!DomainAgentQuestionnaire.isRequest(event)) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("query", context.command().message());
        snapshot.put("routeSource", context.route().routeSource());
        snapshot.put("skillId", context.route().selectedAgentCode());
        snapshot.put("metadata", context.command().metadata());
        snapshot.put("intentAccessName", context.command().intentAccessName());
        snapshot.put("channel", context.command().channel());
        snapshot.put("rerouteCount", context.rerouteCount());
        snapshot.put("rejectedDomainAgentIds", List.copyOf(context.rejectedDomainAgentIds()));
        Set<String> documents = new LinkedHashSet<>();
        context.command().attachments().forEach(ref -> documents.add(ref.documentId()));
        context.documents().forEach(document -> documents.add(document.id()));
        snapshot.put("documentIds", List.copyOf(documents));
        snapshot.values().removeIf(java.util.Objects::isNull);
        snapshot = AgentModeBindingContext.apply(snapshot,
                AgentModeBindingContext.fromBinding(context.bindingRef().get()));
        snapshot = IntentExpertContext.withScope(ChatPayloadMaps.immutableCopy(snapshot),
                context.command().intentExpertScope());
        context.pendingInteractionPayloadRef().set(Map.of(KEY, snapshot));
    }

    static Map<String, Object> require(ChatInteractionRequest interaction) {
        Map<String, Object> snapshot = map(interaction.requestPayload().get(KEY));
        if (snapshot.isEmpty() || text(snapshot.get("skillId")) == null) {
            throw new IllegalStateException("DomainAgent questionnaire lacks trusted continuation context");
        }
        return snapshot;
    }

    static ChatCommand command(ChatInteractionRequest interaction, Map<String, Object> response, String runId) {
        Map<String, Object> snapshot = require(interaction);
        String query = text(snapshot.get("query"));
        StringBuilder question = new StringBuilder(query == null ? "" : query);
        Map<String, Object> labels = map(map(response.get("questionnaireAnswers")).get("label"));
        labels.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                question.append("\n").append(entry.getKey()).append(": ").append(entry.getValue()));
        List<AttachmentRef> attachments = strings(snapshot.get("documentIds")).stream()
                .map(id -> new AttachmentRef(id, null, null, null)).toList();
        return new ChatCommand(runId, interaction.tenantId(), interaction.userId(), interaction.sessionId(),
                null, text(snapshot.get("channel")), question.toString(), attachments, map(snapshot.get("metadata")),
                null, null, ChatRunMode.NEXT, null, null, null, null, null, null, null, Map.of(),
                null, null, AgentModeBindingContext.fromMetadata(snapshot), null, null, text(snapshot.get("intentAccessName")),
                IntentExpertContext.fromMetadata(snapshot).orElse(null));
    }

    static ChatCommand withDocuments(ChatCommand command, List<AttachmentRef> attachments,
                                      Map<String, Object> metadata) {
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                null, command.channel(), command.message(), attachments, metadata, null, null, ChatRunMode.NEXT,
                null, null, null, command.routeTrigger(), null, null, null, Map.of(), null, null,
                command.agentMode(), null, null, command.intentAccessName(), command.intentExpertScope());
    }

    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof String name) {
                result.put(name, item);
            }
        });
        return ChatPayloadMaps.immutableCopy(result);
    }

    static List<String> strings(Object value) {
        return value instanceof List<?> values
                ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }

    static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
