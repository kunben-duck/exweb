/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MessageChannelTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void onlyRequestBoundaryDefaultsToWeb(String input) {
        assertThat(MessageChannel.fromRequest(input)).isEqualTo("web");
        assertThat(MessageChannel.fromRun(null)).isNull();
        assertThat(message().channel()).isNull();
    }

    @Test
    void trimsWithoutChangingCaseOrRestrictingCustomChannels() {
        assertThat(MessageChannel.fromRequest(" Mobile-App ")).isEqualTo("Mobile-App");
        assertThat(MessageChannel.fromRequest("x".repeat(64))).hasSize(64);
        assertThatThrownBy(() -> MessageChannel.fromRequest("x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void privateRunValueCannotBeForgedAndNullBusinessValuesSurvive() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(MessageChannel.RUN_METADATA_KEY, "forged");
        input.put("optional", null);
        Map<String, Object> metadata = MessageChannel.forRun(input, "mobile");
        assertThat(metadata).containsEntry(MessageChannel.RUN_METADATA_KEY, "mobile").containsEntry("optional", null);
        assertThat(input).containsEntry(MessageChannel.RUN_METADATA_KEY, "forged");
        assertThatThrownBy(() -> metadata.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(MessageChannel.forRun(input, null)).containsEntry("optional", null)
                .doesNotContainKey(MessageChannel.RUN_METADATA_KEY);
        ChatCommand command = new ChatCommand("cmd", "tenant", "user", null, null, "mobile", "question",
                List.of(), Map.of(MessageChannel.RUN_METADATA_KEY, "forged", "business", "value"));
        assertThat(command.metadata()).containsOnlyKeys("business");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"mobile", "web", "Custom"})
    void messageCopiesPreserveExactValueAndLegacyJsonRemainsUnknown(String channel) throws Exception {
        ChatMessage source = message().withChannel(channel);
        ChatMessage copied = source.withParts(List.of()).withAttachments(List.of()).withMetadataJson("{}");
        assertThat(copied.channel()).isEqualTo(channel);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        assertThat(mapper.readValue(mapper.writeValueAsString(copied), ChatMessage.class).channel()).isEqualTo(channel);
        var oldJson = mapper.valueToTree(copied);
        ((com.fasterxml.jackson.databind.node.ObjectNode) oldJson).remove("channel");
        assertThat(mapper.treeToValue(oldJson, ChatMessage.class).channel()).isNull();
    }

    private ChatMessage message() {
        return new ChatMessage("msg", "tenant", "user", "session", "user", "question", null, Instant.EPOCH);
    }
}
