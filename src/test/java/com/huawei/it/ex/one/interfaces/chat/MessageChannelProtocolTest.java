/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.interfaces.chat.dto.ChatSelectedIntentDto;
import com.huawei.it.ex.one.interfaces.chat.dto.SwitchDomainAgentRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

class MessageChannelProtocolTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " mobile ", "Custom"})
    void candidateRequestDefaultsAtBoundaryAndNeverInheritsOldSource(String channel) throws Exception {
        SwitchDomainAgentRequest request = request(channel);
        var command = new ChatRequestTranslator().toCandidateSwitchCommand("run", request);
        assertThat(command.channel()).isEqualTo(channel == null || channel.isBlank() ? "web" : channel.trim());
        assertThat(command.metadata()).doesNotContainKey("_messageChannel");
        SwitchDomainAgentRequest decoded = new ObjectMapper().readValue(
                "{\"messageId\":\"msg\",\"skillId\":\"skill\",\"selectedIntent\":{\"intentId\":\"intent\",\"intentName\":\"name\"}}",
                SwitchDomainAgentRequest.class);
        assertThat(new ChatRequestTranslator().toCandidateSwitchCommand("run", decoded).channel()).isEqualTo("web");
    }

    @Test
    void candidateChannelKeepsTheExistingLengthLimit() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request("x".repeat(64)))).isEmpty();
            assertThat(factory.getValidator().validate(request("x".repeat(65))))
                    .anySatisfy(error -> assertThat(error.getPropertyPath().toString()).isEqualTo("channel"));
        }
        assertThatThrownBy(() -> new ChatRequestTranslator().toCandidateSwitchCommand("run", request("x".repeat(65))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openApiAndNewDocumentationExamplesParse() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(4_000_000);
        Map<?, ?> api = new Yaml(new SafeConstructor(options)).load(Files.readString(
                Path.of("docs/openapi/financeex-chatservice-v1.yaml")));
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) api.get("components")).get("schemas");
        for (String name : new String[] {"ChatMessage", "SwitchDomainAgentRequest"}) {
            Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) schemas.get(name)).get("properties");
            Map<?, ?> channel = (Map<?, ?>) properties.get("channel");
            assertThat(channel.get("maxLength")).isEqualTo(64);
            assertThat(channel.get("nullable")).isEqualTo(true);
        }
        String documentation = Files.readString(Path.of("docs/message-channel.md"));
        var examples = Pattern.compile("(?s)```json\\s+(.*?)```").matcher(documentation);
        int count = 0;
        while (examples.find()) {
            assertThat(new ObjectMapper().readTree(examples.group(1)).get("channel").asText()).isEqualTo("mobile");
            count++;
        }
        assertThat(count).isEqualTo(1);
    }

    private SwitchDomainAgentRequest request(String channel) {
        return new SwitchDomainAgentRequest("msg", "skill", new ChatSelectedIntentDto("intent", "name"),
                Map.of("_messageChannel", "forged"), null, null, channel);
    }
}
