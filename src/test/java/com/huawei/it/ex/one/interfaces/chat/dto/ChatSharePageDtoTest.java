package com.huawei.it.ex.one.interfaces.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class ChatSharePageDtoTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void pageResponseKeepsExistingMetadataOnlyShape() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        ChatShareDto item = new ChatShareDto(
                "share1", "分享", "SELECTED_MESSAGES", "INTERNAL", "ACTIVE", null,
                "session1", "msg_user", null, null, now, now);
        ChatSharePageDto page = new ChatSharePageDto(List.of(item), 1, 20, 1L, 1L);

        JsonNode json = objectMapper.valueToTree(page);

        assertThat(fieldNames(json)).containsExactly(
                "items", "curPage", "pageSize", "totalRows", "totalPages");
        assertThat(fieldNames(json.path("items").get(0))).containsExactly(
                "shareId", "title", "scope", "visibility", "status", "expiresAt",
                "sourceSessionId", "sourceUserMessageId", "sourceAssistantMessageId",
                "sourceRunId", "createdAt", "updatedAt");
        assertThat(json.toString()).doesNotContain("snapshot", "question", "answer", "messages", "parts");
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }
}
