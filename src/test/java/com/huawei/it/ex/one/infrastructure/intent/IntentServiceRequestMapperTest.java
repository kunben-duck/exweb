/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.memory.RouteMemoryContext;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class IntentServiceRequestMapperTest {
    @ParameterizedTest
    @CsvSource(value = {
            "NULL, fin, fin",
            "'', fin, fin",
            "'   ', fin, fin",
            "EX_, fin, EX_fin",
            "' EX_ ', ' fin ', EX_fin",
            "ex_, FIN, ex_FIN",
            "EX_, EX_fin, EX_EX_fin",
            "EX_, NULL, configured-entry",
            "EX_, '', configured-entry",
            "EX_, '   ', configured-entry"
    }, nullValues = "NULL")
    void prefixesOnlyExplicitLogicalEntry(String prefix, String entry, String expected) {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setAccessName(" configured-entry ");
        properties.setRequestAccessNamePrefix(prefix);
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(
                properties, new DefaultIntentAccessNameResolver(properties));
        ChatCommand command = command(entry);
        ObjectMapper json = new ObjectMapper();
        var originalCommand = json.valueToTree(command);

        for (int attempt = 0; attempt < 3; attempt++) {
            IntentRecognizeRequest request = mapper.toWireRequest(
                    command, MemoryContext.empty(), new UserContext("tenant1", "user1", "User One"));
            assertThat(request.accessName()).isEqualTo(expected);
        }

        var mappedCommand = json.valueToTree(command);
        assertThat(mappedCommand).isEqualTo(originalCommand);
        assertThat(new DefaultIntentAccessNameResolver(properties).resolve(entry))
                .isEqualTo(entry == null || entry.isBlank() ? "configured-entry" : entry.trim());
    }

    @Test
    void prefixesRestoredExpertEntryWithoutChangingScopeOrOtherRequestFields() {
        IntentExpertScope scope = new IntentExpertScope("tax-expert", "税务专家", "tax_entry");
        ChatCommand command = command(null).withIntentExpertScope(scope);
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(
                properties, new DefaultIntentAccessNameResolver(properties));
        ObjectMapper json = new ObjectMapper();
        ObjectNode before = json.valueToTree(mapper.toWireRequest(
                command, MemoryContext.empty(), new UserContext("tenant1", "user1", "User One")));
        properties.setRequestAccessNamePrefix("EX_");
        properties.setResponseAccessNamePrefix("response_");
        var after = json.valueToTree(mapper.toWireRequest(
                command, MemoryContext.empty(), new UserContext("tenant1", "user1", "User One")));

        assertThat(after.path("accessName").asText()).isEqualTo("EX_tax_entry");
        before.put("accessName", "EX_tax_entry");
        assertThat(after).isEqualTo(before);
        assertThat(command.intentAccessName()).isEqualTo("tax_entry");
        assertThat(command.intentExpertScope()).isEqualTo(scope);
        assertThat(scope.intentAccessName()).isEqualTo("tax_entry");
    }

    @Test
    void doesNotTruncatePrefixedEntryAtFrontendLengthLimit() {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setRequestAccessNamePrefix("EX_");
        var request = new IntentServiceRequestMapper(properties).toWireRequest(
                command("f".repeat(128)), MemoryContext.empty(),
                new UserContext("tenant1", "user1", "User One"));

        assertThat(request.accessName()).isEqualTo("EX_" + "f".repeat(128));
    }

    @Test
    void intentConversationHistoryDoesNotGainAgentRuntimeSkillField() {
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(new IntentServiceHttpProperties());
        RouteMemoryContext routeMemory = new RouteMemoryContext(
                "domain_reject",
                List.of(Map.of(
                        "type", "route",
                        "domainSessionMessages", List.of(
                                new ConversationMemoryMessage("user", "历史问题")))),
                Map.of(),
                "run-route");
        MemoryContext memory = new MemoryContext(List.of(), List.of(), routeMemory, true, List.of());

        IntentRecognizeRequest request = mapper.toWireRequest(
                command(null), memory, new UserContext("tenant1", "user1", "User One"));
        var historyMessage = new ObjectMapper().valueToTree(request)
                .path("conversationContext").path("history").get(0)
                .path("domainSessionMessages").get(0);

        assertThat(historyMessage.path("role").asText()).isEqualTo("user");
        assertThat(historyMessage.path("content").asText()).isEqualTo("历史问题");
        assertThat(historyMessage.has("skillId")).isFalse();
    }

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
