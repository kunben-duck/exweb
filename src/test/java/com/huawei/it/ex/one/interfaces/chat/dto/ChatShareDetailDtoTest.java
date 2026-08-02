package com.huawei.it.ex.one.interfaces.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ChatShareDetailDtoTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void singleTurnResponseOmitsSelectedMessagesField() {
        ChatShareDetailDto detail = new ChatShareDetailDto(
                share("SINGLE_TURN"),
                message("msg_user", "user"),
                message("msg_assistant", "assistant"),
                List.of(),
                List.of());

        JsonNode json = objectMapper.valueToTree(detail);
        List<String> fieldNames = new ArrayList<>();
        json.fieldNames().forEachRemaining(fieldNames::add);

        assertThat(fieldNames).containsExactly("share", "question", "answer", "parts");
        assertThat(json.has("question")).isTrue();
        assertThat(json.has("answer")).isTrue();
        assertThat(json.has("parts")).isTrue();
        assertThat(json.has("messages")).isFalse();
    }

    @Test
    void selectedMessagesResponseContainsNestedMessages() {
        ChatShareSelectedMessageDto message = new ChatShareSelectedMessageDto(
                "msg_user", "session1", null, 1L, "user", "失败问题", "run1",
                null, List.of(), List.of(), null);
        ChatShareDetailDto detail = new ChatShareDetailDto(
                share("SELECTED_MESSAGES"), null, null, List.of(), List.of(message));

        JsonNode json = objectMapper.valueToTree(detail);

        assertThat(json.get("question").isNull()).isTrue();
        assertThat(json.get("answer").isNull()).isTrue();
        assertThat(json.path("messages").get(0).path("messageId").asText()).isEqualTo("msg_user");
        assertThat(json.path("messages").get(0).path("parts").isArray()).isTrue();
    }

    private ChatShareDto share(String scope) {
        return new ChatShareDto("share1", "分享", scope, "INTERNAL", "ACTIVE", null,
                "session1", null, null, null, null, null);
    }

    private ChatShareSnapshotMessageDto message(String messageId, String role) {
        return new ChatShareSnapshotMessageDto(
                messageId, "session1", role, role + " content", "run1", null, List.of(), null);
    }
}
