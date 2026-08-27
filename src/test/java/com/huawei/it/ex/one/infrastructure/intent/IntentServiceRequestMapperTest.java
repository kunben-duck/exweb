package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class IntentServiceRequestMapperTest {
    @Test
    void includesPreferenceCorrectionsAndAlwaysSerializesTheArray() {
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(new IntentServiceHttpProperties());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        IntentUserPreferenceCorrection correction = new IntentUserPreferenceCorrection(
                "原始问题", "支付成功率分析", null, Instant.parse("2026-08-27T02:00:00Z"));

        IntentRecognizeRequest populated = mapper.toWireRequest(
                command(null), MemoryContext.empty(),
                new UserContext("tenant1", "user1", "User One"), "msg-user", List.of(correction));
        IntentRecognizeRequest empty = mapper.toWireRequest(
                command(null), MemoryContext.empty(),
                new UserContext("tenant1", "user1", "User One"), "msg-user");

        assertThat(populated.userPreferenceCorrections()).containsExactly(correction);
        assertThat(objectMapper.valueToTree(populated)
                .path("userPreferenceCorrections").get(0).has("originalIntent")).isFalse();
        assertThat(objectMapper.valueToTree(empty).path("userPreferenceCorrections").isArray()).isTrue();
        assertThat(objectMapper.valueToTree(empty).path("userPreferenceCorrections")).isEmpty();
    }

    @Test
    void frontendIntentAccessNameOverridesConfiguredDefault() {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setAccessName("configured-entry");
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(properties);

        IntentRecognizeRequest request = mapper.toWireRequest(
                command(" Frontend-Entry "), MemoryContext.empty(),
                new UserContext("tenant1", "user1", "User One"));

        assertThat(request.accessName()).isEqualTo("Frontend-Entry");
    }

    @Test
    void blankFrontendIntentAccessNameFallsBackToConfiguredDefault() {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setAccessName(" configured-entry ");
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(properties);

        IntentRecognizeRequest request = mapper.toWireRequest(
                command("   "), MemoryContext.empty(),
                new UserContext("tenant1", "user1", "User One"));

        assertThat(request.accessName()).isEqualTo("configured-entry");
    }

    @Test
    void agentModeNeverEntersIntentWireRequest() {
        ChatCommand command = new ChatCommand(
                "cmd1", "tenant1", "user1", "session1", null, "web", "分析资金情况",
                List.of(), Map.of("scene", "fund"), null, null, ChatRunMode.NEXT,
                null, null, null, null, null, null, null, Map.of(), null, null,
                new AgentModeProfile(List.of(new AgentModeSelection("thinking", "deep", "深度思考"))));
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(new IntentServiceHttpProperties());

        IntentRecognizeRequest request = mapper.toWireRequest(
                command, MemoryContext.empty(), new UserContext("tenant1", "user1", "User One"));

        assertThat(request.query()).isEqualTo("分析资金情况");
        assertThat(new ObjectMapper().valueToTree(request).has("agentMode")).isFalse();
    }

    @Test
    void trustedMessageIdIsIncludedAndMissingValueIsOmitted() {
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(new IntentServiceHttpProperties());
        ObjectMapper objectMapper = new ObjectMapper();

        IntentRecognizeRequest associated = mapper.toWireRequest(
                command(null), MemoryContext.empty(),
                new UserContext("tenant1", "user1", "User One"), " msg-user ");
        IntentRecognizeRequest compatible = mapper.toWireRequest(
                command(null), MemoryContext.empty(),
                new UserContext("tenant1", "user1", "User One"));

        assertThat(associated.messageId()).isEqualTo("msg-user");
        assertThat(objectMapper.valueToTree(associated).path("messageId").asText()).isEqualTo("msg-user");
        assertThat(compatible.messageId()).isNull();
        assertThat(objectMapper.valueToTree(compatible).has("messageId")).isFalse();
    }

    private ChatCommand command(String intentAccessName) {
        return new ChatCommand(
                "cmd1", "tenant1", "user1", "session1", null, "web", "分析资金情况",
                List.of(), Map.of("scene", "fund"), null, null, ChatRunMode.NEXT,
                null, null, null, null, null, null, null, Map.of(), null, null,
                null, null, null, intentAccessName);
    }
}
