package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** 会话私有标题总结状态的JSON编解码器。 */
@Component
final class SessionTitleMetadata {
    static final String METADATA_KEY = "_titleSummary";

    private final ObjectMapper objectMapper;
    private final SessionTitleProperties properties;

    SessionTitleMetadata(ObjectMapper objectMapper, SessionTitleProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    boolean enabled() {
        return properties.isEnabled();
    }

    String initialize(String metadataJson, SessionTitleSummarySource source) {
        if (!enabled()) {
            return metadataJson;
        }
        return write(metadataJson, new SessionTitleSummaryState(source, 0, 0L));
    }

    String markUser(String metadataJson) {
        if (!enabled()) {
            return metadataJson;
        }
        SessionTitleSummaryState current = read(metadataJson)
                .orElse(new SessionTitleSummaryState(SessionTitleSummarySource.USER, 0, 0L));
        return write(metadataJson, new SessionTitleSummaryState(
                SessionTitleSummarySource.USER,
                current.appliedQueryCount(),
                current.appliedNodeOrder()));
    }

    String markAuto(String metadataJson, int queryCount, long nodeOrder) {
        return write(metadataJson, new SessionTitleSummaryState(
                SessionTitleSummarySource.AUTO, queryCount, nodeOrder));
    }

    Optional<SessionTitleSummaryState> read(String metadataJson) {
        ObjectNode root = parseRoot(metadataJson).orElse(null);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode state = root.get(METADATA_KEY);
        if (state == null || !state.isObject()) {
            return Optional.empty();
        }
        try {
            SessionTitleSummarySource source = SessionTitleSummarySource.valueOf(state.path("source").asText(""));
            int queryCount = state.path("appliedQueryCount").asInt(0);
            long nodeOrder = state.path("appliedNodeOrder").asLong(0L);
            return Optional.of(new SessionTitleSummaryState(source, queryCount, nodeOrder));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String write(String metadataJson, SessionTitleSummaryState state) {
        Optional<ObjectNode> parsedRoot = parseRoot(metadataJson);
        if (parsedRoot.isEmpty()) {
            // 非法存量metadata按受保护数据处理，不能为了标题状态丢弃原内容。
            return metadataJson;
        }
        ObjectNode root = parsedRoot.get();
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("source", state.source().name());
        summary.put("appliedQueryCount", state.appliedQueryCount());
        summary.put("appliedNodeOrder", state.appliedNodeOrder());
        root.set(METADATA_KEY, summary);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("会话标题总结状态序列化失败", ex);
        }
    }

    private Optional<ObjectNode> parseRoot(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Optional.of(objectMapper.createObjectNode());
        }
        try {
            JsonNode parsed = objectMapper.readTree(metadataJson);
            return parsed != null && parsed.isObject()
                    ? Optional.of((ObjectNode) parsed)
                    : Optional.empty();
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }
}
