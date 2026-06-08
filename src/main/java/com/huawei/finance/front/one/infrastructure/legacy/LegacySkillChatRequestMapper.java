package com.huawei.finance.front.one.infrastructure.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.LegacySkillProperties;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentRequest;
import com.huawei.finance.front.one.domain.document.DocumentSource;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 老 Agent chat wire DTO 映射器。
 *
 * <p>ChatService 对内只使用 documentId、UploadedDocument 和 selectedSkillId；老接口需要的
 * platform、steamFlag 和 sceneParam.docList 等历史字段集中在这里生成，避免污染主编排。</p>
 */
@Component
public class LegacySkillChatRequestMapper {
    private final ObjectMapper objectMapper;
    private final LegacySkillProperties properties;

    public LegacySkillChatRequestMapper(ObjectMapper objectMapper, LegacySkillProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> toWireRequest(LegacySkillAgentRequest request) {
        Map<String, Object> legacyOptions = legacyOptions(request.metadata());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isThink", intOption(legacyOptions, "isThink", properties.getDefaultIsThink()));
        body.put("platform", stringOption(legacyOptions, "platform", properties.getDefaultPlatform()));
        body.put("queryType", stringOption(legacyOptions, "queryType", properties.getDefaultQueryType()));
        body.put("query", request.query());
        body.put("sceneParam", Map.of("docList", docList(request.documents())));
        body.put("sessionId", request.sessionId());
        body.put("skillId", request.skillId());
        body.put("steamFlag", stringOption(legacyOptions, "streamFlag", properties.getDefaultStreamFlag()));
        body.put("supMsg", stringOption(legacyOptions, "supMsg", ""));
        return body;
    }

    private List<Map<String, Object>> docList(List<UploadedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        if (documents.size() > properties.normalizedMaxAttachments()) {
            throw new IllegalArgumentException("指定技能附件数量超过上限: " + properties.normalizedMaxAttachments());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (UploadedDocument document : documents) {
            if (!DocumentSource.LEGACY_AGENT_UPLOAD.name().equals(document.source())) {
                throw new IllegalArgumentException("指定技能仅支持 legacy-agent provider 上传的文档: " + document.id());
            }
            Map<String, Object> providerDocument = providerDocument(document);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", stringValue(providerDocument.get("docId"), document.objectKey()));
            item.put("docName", stringValue(providerDocument.get("docName"), document.originalName()));
            item.put("docRelativePath", stringValue(providerDocument.get("docRelativePath"), ""));
            item.put("docSize", longValue(providerDocument.get("docSize"), document.sizeBytes()));
            item.put("levelCode", stringValue(providerDocument.get("levelCode"), ""));
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> providerDocument(UploadedDocument document) {
        try {
            JsonNode root = objectMapper.readTree(document.metadataJson() == null ? "{}" : document.metadataJson());
            String providerCode = text(root, "providerCode");
            if (!"legacy-agent".equals(providerCode)) {
                throw new IllegalArgumentException("文档 provider 不是 legacy-agent: " + document.id());
            }
            JsonNode providerDocument = root.get("providerDocument");
            if (providerDocument == null || !providerDocument.isObject()) {
                throw new IllegalArgumentException("legacy-agent 文档缺少 providerDocument 元数据: " + document.id());
            }
            Map<String, Object> values = new LinkedHashMap<>();
            providerDocument.fields().forEachRemaining(entry -> values.put(entry.getKey(), jsonValue(entry.getValue())));
            return values;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("legacy-agent 文档元数据解析失败: " + document.id(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> legacyOptions(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("legacyAgent");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private int intOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String stringOption(Map<String, Object> options, String key, String defaultValue) {
        return stringValue(options.get(key), defaultValue);
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText("");
    }
}
