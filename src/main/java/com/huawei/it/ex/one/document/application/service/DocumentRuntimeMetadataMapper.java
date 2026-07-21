package com.huawei.it.ex.one.document.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Package-local mapping of trusted provider documents into Runtime metadata. */
final class DocumentRuntimeMetadataMapper {
    private final ObjectMapper objectMapper;

    DocumentRuntimeMetadataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, Object> replaceDocuments(
            Map<String, Object> metadata,
            List<UploadedDocument> documents) {
        Map<String, Object> result = mutableDeepCopy(metadata);
        Object sceneValue = result.get("sceneParam");
        if (documents == null || documents.isEmpty()) {
            if (sceneValue instanceof Map<?, ?> sceneMap) {
                Map<String, Object> sceneCopy = mutableMap(sceneMap);
                sceneCopy.remove("docList");
                result.put("sceneParam", sceneCopy);
            }
            return immutableDeepCopy(result);
        }
        if (sceneValue != null && !(sceneValue instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("metadata.sceneParam 必须是 JSON object");
        }
        Map<String, Object> scene = sceneValue instanceof Map<?, ?> sceneMap
                ? mutableMap(sceneMap)
                : new LinkedHashMap<>();
        List<Map<String, Object>> docList = documents.stream()
                .filter(Objects::nonNull)
                .map(this::providerDocumentReference)
                .toList();
        scene.put("docList", docList);
        result.put("sceneParam", scene);
        return immutableDeepCopy(result);
    }

    private Map<String, Object> providerDocumentReference(UploadedDocument document) {
        try {
            JsonNode root = objectMapper.readTree(document.metadataJson() == null ? "{}" : document.metadataJson());
            JsonNode providerDocument = root == null ? null : root.get("providerDocument");
            if (providerDocument == null || !providerDocument.isObject()) {
                throw new IllegalArgumentException("文档缺少 providerDocument 元数据: " + document.id());
            }
            String docId = textValue(providerDocument.get("docId"));
            String url = textValue(providerDocument.get("url"));
            if (docId == null && url == null) {
                throw new IllegalArgumentException("文档 providerDocument 必须包含 docId 或 url: " + document.id());
            }
            Map<String, Object> reference = objectMapper.convertValue(
                    providerDocument, new TypeReference<Map<String, Object>>() { });
            return immutableDeepCopy(reference);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("文档 providerDocument 元数据解析失败: " + document.id(), ex);
        }
    }

    private String textValue(JsonNode value) {
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Map<String, Object> mutableDeepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null) {
                    copy.put(key, mutableDeepCopyValue(value));
                }
            });
        }
        return copy;
    }

    private Map<String, Object> mutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(String.valueOf(key), mutableDeepCopyValue(value));
            }
        });
        return copy;
    }

    private Object mutableDeepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return mutableMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(mutableDeepCopyValue(item)));
            return copy;
        }
        return value;
    }

    private Map<String, Object> immutableDeepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableDeepCopyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private Object immutableDeepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (key != null) {
                    copy.put(String.valueOf(key), immutableDeepCopyValue(nested));
                }
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(immutableDeepCopyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
