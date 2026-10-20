/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessagePartDto;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class HistoryMobileCardViewAssemblerTest {
    private static final String MOBILE = "https://example.com/mobile/a.js";
    private final DomainAgentProperties properties = new DomainAgentProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HistoryMobileCardViewAssembler assembler = new HistoryMobileCardViewAssembler(properties, mapper);

    @Test
    void disabledOrEmptyConfigurationReturnsOriginalViewsWithoutReadingMetadata() {
        ObjectMapper unusedMapper = mock(ObjectMapper.class);
        HistoryMobileCardViewAssembler view = new HistoryMobileCardViewAssembler(properties, unusedMapper);
        List<ChatMessagePartDto> parts = List.of(part(payload()));
        properties.setHistoryMobileCardUrls(Map.of("skill-a", MOBILE));
        assertThat(view.enrich(message("{broken"), parts)).isSameAs(parts);
        properties.setHistoryMobileCardFallbackEnabled(true);
        properties.setHistoryMobileCardUrls(Map.of());
        assertThat(view.enrich(message("{broken"), parts)).isSameAs(parts);
        verifyNoInteractions(unusedMapper);
    }

    @Test
    void copiesOnlyMatchingPayloadAndPreservesNullsNestedValuesAndOldParts() {
        enable();
        Map<String, Object> payload = payload();
        payload.put("optionalDetail", null);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("optional", null);
        payload.put("detail", nested);
        ChatMessagePartDto original = part(payload);
        ChatMessagePartDto existing = part(Map.of("source", "domain-agent", "cardUrl", "other-web",
                "mobileCardUrl", "already-present"));
        List<ChatMessagePartDto> parts = List.of(original, existing);
        ChatMessage message = message("{\"skillId\":\"skill-a\",\"runDurationMs\":42}");

        List<ChatMessagePartDto> result = assembler.enrich(message, parts);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).usingRecursiveComparison().ignoringFields("payload").isEqualTo(original);
        assertThat(result.get(0).createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(result.get(0).payload()).containsEntry("mobileCardUrl", MOBILE).containsEntry("optionalDetail", null);
        assertThat(result.get(0).payload().get("detail")).isSameAs(nested);
        assertThat(result.get(1)).isSameAs(existing);
        assertThat(payload).doesNotContainKey("mobileCardUrl");
        assertThat(message.metadataJson()).isEqualTo("{\"skillId\":\"skill-a\",\"runDurationMs\":42}");
        assertThatThrownBy(() -> result.get(0).payload().put("changed", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.add(original)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n"})
    void fillsOnlyMissingOrBlankMobileAddress(String current) {
        enable();
        Map<String, Object> payload = payload();
        payload.put("mobileCardUrl", current);
        assertThat(assembler.enrich(message("{\"skillId\":\"skill-a\"}"), List.of(part(payload)))
                .getFirst().payload()).containsEntry("mobileCardUrl", MOBILE);
        assertThat(payload.get("mobileCardUrl")).isEqualTo(current);
    }

    @ParameterizedTest
    @MethodSource("existingMobileValues")
    void preservesExistingAddressesAndWrongTypes(Object current) {
        enable();
        Map<String, Object> payload = payload();
        payload.put("mobileCardUrl", current);
        List<ChatMessagePartDto> parts = List.of(part(payload));
        assertThat(assembler.enrich(message("{\"skillId\":\"skill-a\"}"), parts)).isSameAs(parts);
    }

    private static Stream<Object> existingMobileValues() {
        return Stream.of("https://existing.example/card.js", " existing ", 123, false, List.of(), Map.of());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null", "[]", "broken-json", "{}", "{\"skillId\":12}",
            "{\"skillId\":null}", "{\"skillId\":\"\"}", "{\"skillId\":\"other\"}", "{\"skillId\":\"SKILL-A\"}"})
    void keepsMissingInvalidAndUnmatchedMessageSkillUnchanged(String metadata) {
        enable();
        List<ChatMessagePartDto> parts = List.of(part(payload()));
        assertThat(assembler.enrich(message(metadata), parts)).isSameAs(parts);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "skill-a"})
    void acceptsAbsentBlankOrMatchingCardSkill(String cardSkill) {
        enable();
        Map<String, Object> payload = payload();
        payload.put("domainAgentId", cardSkill);
        assertThat(assembler.enrich(message("{\"skillId\":\"skill-a\"}"), List.of(part(payload)))
                .getFirst().payload()).containsEntry("mobileCardUrl", MOBILE);
    }

    @ParameterizedTest
    @MethodSource("conflictingCardSkills")
    void skipsConflictingAndMalformedCardSkill(Object cardSkill) {
        enable();
        Map<String, Object> payload = payload();
        payload.put("domainAgentId", cardSkill);
        List<ChatMessagePartDto> parts = List.of(part(payload));
        assertThat(assembler.enrich(message("{\"skillId\":\"skill-a\"}"), parts)).isSameAs(parts);
    }

    private static Stream<Object> conflictingCardSkills() {
        return Stream.of("skill-b", "SKILL-A", 123, false, List.of("skill-a"), Map.of("id", "skill-a"));
    }

    @Test
    void skipsNonUrlNonDomainAgentAndNonCardPartsWithoutParsingMetadata() {
        enable();
        ObjectMapper unusedMapper = mock(ObjectMapper.class);
        HistoryMobileCardViewAssembler view = new HistoryMobileCardViewAssembler(properties, unusedMapper);
        for (Map<String, Object> payload : List.of(
                Map.<String, Object>of("source", "relay", "cardUrl", "web"),
                Map.<String, Object>of("source", "domain-agent", "diyCardScene", "scene"),
                Map.<String, Object>of("source", "domain-agent", "cardUrl", ""),
                Map.<String, Object>of("source", "domain-agent", "cardUrl", 12),
                Map.<String, Object>of("cardUrl", "web"))) {
            List<ChatMessagePartDto> parts = List.of(part(payload));
            assertThat(view.enrich(message("{\"skillId\":\"skill-a\"}"), parts)).isSameAs(parts);
        }
        ChatMessagePartDto card = part(payload());
        List<ChatMessagePartDto> thinking = List.of(new ChatMessagePartDto(card.partId(), card.messageId(),
                card.runId(), "THINKING", card.sourceType(), card.contentText(), card.title(), card.status(),
                card.channel(), card.displayHint(), card.visible(), card.payload(), card.partOrder(), card.createdAt()));
        assertThat(view.enrich(message("{\"skillId\":\"skill-a\"}"), thinking)).isSameAs(thinking);
        ChatMessage user = new ChatMessage("u", "tenant", "user", "session", "user", "", 0, Instant.EPOCH)
                .withMetadataJson("{\"skillId\":\"skill-a\"}");
        List<ChatMessagePartDto> parts = List.of(card);
        assertThat(view.enrich(user, parts)).isSameAs(parts);
        verifyNoInteractions(unusedMapper);
    }

    @Test
    void resolvesMultipleSkillsRegardlessOfWebAddressAndCanBeDisabledWithoutChangingOriginal() {
        enable();
        properties.setHistoryMobileCardUrls(Map.of("skill-a", MOBILE, "skill-b", "https://example.com/mobile/b.js",
                "empty", "  "));
        List<ChatMessagePartDto> parts = List.of(part(payload()));
        assertThat(assembler.enrich(message("{\"skillId\":\"skill-b\"}"), parts).getFirst().payload())
                .containsEntry("mobileCardUrl", "https://example.com/mobile/b.js");
        assertThat(assembler.enrich(message("{\"skillId\":\"empty\"}"), parts)).isSameAs(parts);
        properties.setHistoryMobileCardFallbackEnabled(false);
        assertThat(assembler.enrich(message("{\"skillId\":\"skill-a\"}"), parts)).isSameAs(parts);
        assertThat(parts.getFirst().payload()).doesNotContainKey("mobileCardUrl");
    }

    @Test
    void fallbackFailureReturnsOriginalViewsWithoutPartiallyChangingThem() {
        DomainAgentProperties failing = mock(DomainAgentProperties.class);
        when(failing.isHistoryMobileCardFallbackEnabled()).thenReturn(true);
        when(failing.getHistoryMobileCardUrls()).thenThrow(new IllegalStateException("injected"));
        List<ChatMessagePartDto> parts = List.of(part(payload()));
        assertThat(new HistoryMobileCardViewAssembler(failing, mapper)
                .enrich(message("{\"skillId\":\"skill-a\"}"), parts)).isSameAs(parts);
        assertThat(parts.getFirst().payload()).doesNotContainKey("mobileCardUrl");
    }

    private void enable() {
        properties.setHistoryMobileCardFallbackEnabled(true);
        properties.setHistoryMobileCardUrls(Map.of("skill-a", MOBILE));
    }

    private ChatMessage message(String metadata) {
        return new ChatMessage("message", "tenant", "user", "session", "assistant", "answer", 1, Instant.EPOCH)
                .withMetadataJson(metadata);
    }

    private Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("cardUrl", "https://unmatched-web.example/card.js");
        return payload;
    }

    private ChatMessagePartDto part(Map<String, Object> payload) {
        return new ChatMessagePartDto("part", "message", "run", "CARD", "cardUrl", "summary", "card",
                "STREAMING", "card", "inline", true, payload, 2, Instant.EPOCH);
    }
}
