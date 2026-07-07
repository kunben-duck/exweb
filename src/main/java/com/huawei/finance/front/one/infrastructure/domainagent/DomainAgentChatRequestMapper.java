package com.huawei.finance.front.one.infrastructure.domainagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.DomainAgentProperties;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentRequest;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DomainAgent chat wire DTO 映射器。
 *
 * <p>DomainAgent 的请求体由前端 {@code metadata} 完整决定，本 mapper 不再补默认字段或重组
 * sceneParam。ChatService 只在边界处校验 docList 中的 docId/url 必须来自已鉴权附件，避免伪造文档引用。</p>
 */
@Component
public class DomainAgentChatRequestMapper {
    private final ObjectMapper objectMapper;
    private final DomainAgentProperties properties;

    public DomainAgentChatRequestMapper(ObjectMapper objectMapper, DomainAgentProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> toWireRequest(DomainAgentRequest request) {
        Map<String, Object> body = deepCopyMap(request.metadata());
        validateDocList(body, request.documents());
        return body;
    }

    private void validateDocList(Map<String, Object> body, List<UploadedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            Object sceneParam = body.get("sceneParam");
            Object docList = sceneParam instanceof Map<?, ?> sceneMap ? sceneMap.get("docList") : null;
            if (docList instanceof List<?> list && !list.isEmpty()) {
                throw new IllegalArgumentException("metadata.sceneParam.docList 引用了未授权文档，请同时在 attachments 中传入 documentId");
            }
            return;
        }
        if (documents.size() > properties.normalizedMaxAttachments()) {
            throw new IllegalArgumentException("DomainAgent 附件数量超过上限: " + properties.normalizedMaxAttachments());
        }
        Object sceneParam = body.get("sceneParam");
        if (!(sceneParam instanceof Map<?, ?> sceneMap)) {
            throw new IllegalArgumentException("metadata.sceneParam 必须是 JSON object，并包含与 attachments 匹配的 docList");
        }
        Object docList = sceneMap.get("docList");
        if (!(docList instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("metadata.sceneParam.docList 不能为空，且必须与 attachments 中的文档匹配");
        }
        List<Map<String, Object>> authorizedDocuments = documents.stream()
                .map(this::providerDocument)
                .toList();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException("metadata.sceneParam.docList 每一项必须是 JSON object");
            }
            String docId = stringValue(itemMap.get("docId"), "");
            String url = stringValue(itemMap.get("url"), "");
            if (docId.isBlank() && url.isBlank()) {
                throw new IllegalArgumentException("metadata.sceneParam.docList 每一项必须包含 docId 或 url");
            }
            if (authorizedDocuments.stream().anyMatch(provider -> documentReferenceMatches(provider, docId, url))) {
                continue;
            }
            throw new IllegalArgumentException("metadata.sceneParam.docList 包含未授权文档引用: "
                    + (docId.isBlank() ? url : docId));
        }
    }

    private boolean documentReferenceMatches(Map<String, Object> provider, String docId, String url) {
        String providerDocId = stringValue(provider.get("docId"), "");
        String providerUrl = stringValue(provider.get("url"), "");
        boolean docIdMatches = docId.isBlank() || (!providerDocId.isBlank() && providerDocId.equals(docId));
        boolean urlMatches = url.isBlank() || (!providerUrl.isBlank() && providerUrl.equals(url));
        return docIdMatches && urlMatches;
    }

    private Map<String, Object> providerDocument(UploadedDocument document) {
        try {
            JsonNode root = objectMapper.readTree(document.metadataJson() == null ? "{}" : document.metadataJson());
            JsonNode providerDocument = root.get("providerDocument");
            if (providerDocument == null || !providerDocument.isObject()) {
                throw new IllegalArgumentException("DomainAgent 文档缺少 providerDocument 元数据: " + document.id());
            }
            return objectMapper.convertValue(providerDocument, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("domain-agent 文档元数据解析失败: " + document.id(), ex);
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
