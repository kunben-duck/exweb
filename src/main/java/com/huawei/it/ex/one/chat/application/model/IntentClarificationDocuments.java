package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal document-id metadata used only across intent clarification turns. */
public final class IntentClarificationDocuments {
    public static final String METADATA_KEY = "_intentClarificationDocumentIds";

    private IntentClarificationDocuments() {
    }

    public static List<String> fromPayload(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get(METADATA_KEY);
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (Object item : values) {
            if (item != null && !String.valueOf(item).isBlank()) {
                ids.putIfAbsent(String.valueOf(item).trim(), Boolean.TRUE);
            }
        }
        return List.copyOf(ids.keySet());
    }

    public static List<String> fromDocuments(List<UploadedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (UploadedDocument document : documents) {
            if (document != null && document.id() != null && !document.id().isBlank()) {
                ids.putIfAbsent(document.id(), Boolean.TRUE);
            }
        }
        return List.copyOf(ids.keySet());
    }

    public static Map<String, Object> withoutInternalIds(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty() || !payload.containsKey(METADATA_KEY)) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.remove(METADATA_KEY);
        return ChatPayloadMaps.immutableCopy(copy);
    }
}
