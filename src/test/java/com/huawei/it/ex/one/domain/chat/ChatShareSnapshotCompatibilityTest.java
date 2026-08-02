package com.huawei.it.ex.one.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class ChatShareSnapshotCompatibilityTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void oldSingleTurnJsonDefaultsSelectedMessagesToEmptyList() throws Exception {
        String oldJson = """
                {
                  "question": null,
                  "answer": null,
                  "parts": [],
                  "createdAt": "2026-08-02T10:00:00Z"
                }
                """;

        ChatShareSnapshot snapshot = objectMapper.readValue(oldJson, ChatShareSnapshot.class);

        assertThat(snapshot.messages()).isEmpty();
    }

    @Test
    void singleTurnJsonStillOmitsEmptySelectedMessages() throws Exception {
        ChatShareSnapshot snapshot = new ChatShareSnapshot(
                null, null, List.of(), Instant.parse("2026-08-02T10:00:00Z"));

        String json = objectMapper.writeValueAsString(snapshot);

        assertThat(json).doesNotContain("\"messages\"");
    }
}
