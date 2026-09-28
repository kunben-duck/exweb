/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.domain.document.UploadedDocument;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DomainAgent chat wire DTO 映射器。
 *
 * <p>DomainAgent 的请求体以前端 {@code metadata} 作为业务扩展，但 {@code messageId/skillId/query/sessionId}
 * 是会话绑定正确性的保留字段，必须由服务端按当前绑定强制覆盖。标准附件由应用层独立完成权限校验；
 * {@code docList} 作为业务 metadata 只在此检查基本结构。</p>
 */
@Component
public class DomainAgentChatRequestMapper {
    private final DomainAgentProperties properties;

    public DomainAgentChatRequestMapper(DomainAgentProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> toWireRequest(DomainAgentRequest request) {
        Map<String, Object> body = deepCopyMap(request.metadata());
        // Gate 已前置数量检查，这里保留出站最后防线，兼容不经过标准路由的调用入口。
        validateAttachmentCount(request.documents());
        validateDocListStructure(body);
        Map<String, Object> next = new LinkedHashMap<>(body);
        // runId/messageId只能来自ChatService事实，不能信任前端metadata中的同名字段。
        next.remove("runId");
        next.remove("messageId");
        if (request.runId() != null && !request.runId().isBlank()) {
            next.put("runId", request.runId().trim());
        }
        if (request.messageId() != null) {
            next.put("messageId", request.messageId());
        }
        if (request.shortTermMemoryEnabled()) {
            next.put("messages", request.messages());
        }
        // 当前技能由最终路由覆盖 metadata；历史 messages[].skillId 则来自各轮已保存的 assistant。
        next.put("skillId", request.domainAgentId());
        next.put("query", request.query());
        next.put("sessionId", sessionId(request));
        return Collections.unmodifiableMap(next);
    }

    public Map<String, Object> toInteractionWireRequest(AgentRuntimeInteractionResponseRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "approval-response");
        body.put("runId", requiredText(request.runId()));
        body.put("messageId", requiredText(request.runtimeMetadata().get("userMessageId")));
        body.put("sessionId", requiredText(request.runtimeSessionId()));
        body.put("skillId", requiredText(request.runtimeMetadata().get("skillId")));
        body.put("request_id", requiredText(request.approvalId()));
        Object approved = request.responsePayload().get("approved");
        Object answers = request.responsePayload().get("questionnaireAnswers");
        if (!(approved instanceof Boolean) || !(answers instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("DomainAgent questionnaire response is incomplete");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(approved) && values.get("label") instanceof Map<?, ?> labels && !labels.isEmpty()) {
            normalized.put("label", labels);
            normalized.put("ignore", false);
        } else if (Boolean.FALSE.equals(approved) && Boolean.TRUE.equals(values.get("ignore"))) {
            normalized.put("ignore", true);
        } else {
            throw new IllegalArgumentException("DomainAgent questionnaire response is invalid");
        }
        body.put("approved", approved);
        body.put("scope", "once");
        body.put("questionnaire_answers", normalized);
        return Collections.unmodifiableMap(body);
    }

    private String requiredText(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("DomainAgent questionnaire requires trusted identifiers");
        }
        return text;
    }

    private String sessionId(DomainAgentRequest request) {
        return request.runtimeSessionId() == null || request.runtimeSessionId().isBlank()
                ? request.sessionId()
                : request.runtimeSessionId();
    }

    private void validateAttachmentCount(List<UploadedDocument> documents) {
        if (documents == null) {
            return;
        }
        if (documents.size() > properties.normalizedMaxAttachments()) {
            throw new IllegalArgumentException("DomainAgent 附件数量超过上限: " + properties.normalizedMaxAttachments());
        }
    }

    private void validateDocListStructure(Map<String, Object> body) {
        Object sceneParam = body.get("sceneParam");
        if (!(sceneParam instanceof Map<?, ?> sceneMap) || !sceneMap.containsKey("docList")) {
            return;
        }
        Object docList = sceneMap.get("docList");
        if (!(docList instanceof List<?> list)) {
            throw new IllegalArgumentException("metadata.sceneParam.docList 必须是 JSON array");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException("metadata.sceneParam.docList 每一项必须是 JSON object");
            }
            String docId = stringValue(itemMap.get("docId"), "");
            String url = stringValue(itemMap.get("url"), "");
            if (docId.isBlank() && url.isBlank()) {
                throw new IllegalArgumentException("metadata.sceneParam.docList 每一项必须包含 docId 或 url");
            }
        }
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null) {
                copy.put(key, deepCopy(value));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (key != null) {
                    copy.put(String.valueOf(key), deepCopy(nested));
                }
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(deepCopy(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
