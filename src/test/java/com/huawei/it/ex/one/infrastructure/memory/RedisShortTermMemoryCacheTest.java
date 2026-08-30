/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

class RedisShortTermMemoryCacheTest {
    private static final String CACHE_KEY =
            "fin_ex:test:memory:short_term:messages:tenant1:user1:session1";

    @Test
    void appendUsesDefaultFiveTurnWindow() {
        CacheFixture fixture = fixture(true, 5);

        assertThat(fixture.cache().append(message(1))).isTrue();

        verify(fixture.operations()).trim(CACHE_KEY, -10, -1);
        verify(fixture.redis()).expire(CACHE_KEY, fixture.properties().getTtl());
    }

    @Test
    void appendUsesConfiguredRecentTurnsAsTheOnlyWindow() {
        CacheFixture fixture = fixture(true, 3);

        assertThat(fixture.cache().append(message(1))).isTrue();

        verify(fixture.operations()).trim(CACHE_KEY, -6, -1);
    }

    @Test
    void appendNormalizesNonPositiveRecentTurnsToOneTurn() {
        for (int recentTurns : List.of(0, -3)) {
            CacheFixture fixture = fixture(true, recentTurns);

            assertThat(fixture.cache().append(message(1))).isTrue();

            verify(fixture.operations()).trim(CACHE_KEY, -2, -1);
        }
    }

    @Test
    void replaceSessionMessagesKeepsOnlyTheNewestConfiguredWindowInOrder() throws Exception {
        CacheFixture fixture = fixture(true, 3);
        List<ChatMessage> messages = IntStream.rangeClosed(1, 8)
                .mapToObj(this::message)
                .toList();

        fixture.cache().replaceSessionMessages("tenant1", "user1", "session1", messages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> values = ArgumentCaptor.forClass(Collection.class);
        verify(fixture.operations()).rightPushAll(eq(CACHE_KEY), values.capture());
        List<String> messageIds = values.getValue().stream()
                .map(value -> messageId(fixture.objectMapper(), value))
                .toList();
        assertThat(messageIds).containsExactly("msg3", "msg4", "msg5", "msg6", "msg7", "msg8");
    }

    @Test
    void disabledShortTermMemoryDoesNotAccessRedis() {
        CacheFixture fixture = fixture(false, 5);

        assertThat(fixture.cache().append(message(1))).isFalse();

        verifyNoInteractions(fixture.redis());
    }

    @Test
    void disabledCacheDoesNotAccessRedis() {
        CacheFixture fixture = fixture(true, false, 5);

        assertThat(fixture.cache().append(message(1))).isFalse();
        assertThat(fixture.cache().findRecentMessages(
                "tenant1", "user1", "session1", "msg1", 2)).isEmpty();

        verifyNoInteractions(fixture.redis());
    }

    @Test
    void cachedEntryContainsOnlyCompactMemoryFields() throws Exception {
        CacheFixture fixture = fixture(true, 5);

        fixture.cache().append(message(1));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(fixture.operations()).rightPush(eq(CACHE_KEY), value.capture());
        Map<String, Object> serialized = fixture.objectMapper().readValue(
                value.getValue(), new TypeReference<>() { });
        assertThat(serialized.keySet()).containsExactlyInAnyOrder(
                "messageId", "parentMessageId", "nodeOrder", "runId", "role",
                "content", "memoryEligible", "createdAt");
        assertThat(serialized).doesNotContainKeys(
                "tenantId", "userId", "sessionId", "metadataJson", "parts", "attachments");
    }

    @Test
    void cachedAssistantPreservesNormalizedSkillWithoutFullMetadata() throws Exception {
        CacheFixture fixture = fixture(true, 5);

        assertThat(fixture.cache().append(assistantMessage())).isTrue();

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(fixture.operations()).rightPush(eq(CACHE_KEY), value.capture());
        JsonNode cached = fixture.objectMapper().readTree(value.getValue());
        assertThat(cached.path("skillId").asText()).isEqualTo("skill-a");
        assertThat(cached.has("metadataJson")).isFalse();

        when(fixture.operations().range(CACHE_KEY, -1, -1)).thenReturn(List.of(value.getValue()));
        List<ChatMessage> messages = fixture.cache().findRecentMessages(
                "tenant1", "user1", "session1", "msg-assistant", 1);

        assertThat(messages).singleElement().satisfies(message ->
                assertThat(fixture.objectMapper().readTree(message.metadataJson()).path("skillId").asText())
                        .isEqualTo("skill-a"));
    }

    @Test
    void requestLargerThanCacheWindowBypassesRedis() {
        CacheFixture fixture = fixture(true, 2);

        assertThat(fixture.cache().findRecentMessages(
                "tenant1", "user1", "session1", "msg5", 6)).isEmpty();

        verifyNoInteractions(fixture.redis());
    }

    @Test
    void validCachedPathIsReturnedInReadingOrder() throws Exception {
        CacheFixture fixture = fixture(true, 3);
        when(fixture.operations().range(CACHE_KEY, -3, -1)).thenReturn(List.of(
                cacheEntry(fixture.objectMapper(), "msg1", null, 1, "user", "question"),
                cacheEntry(fixture.objectMapper(), "msg2", "msg1", 2, "assistant", "answer"),
                cacheEntry(fixture.objectMapper(), "msg3", "msg2", 3, "user", "follow up")));

        List<ChatMessage> messages = fixture.cache().findRecentMessages(
                "tenant1", "user1", "session1", "msg3", 3);

        assertThat(messages).extracting(ChatMessage::id)
                .containsExactly("msg1", "msg2", "msg3");
        assertThat(messages).extracting(ChatMessage::content)
                .containsExactly("question", "answer", "follow up");
    }

    @Test
    void staleLeafAndBrokenParentChainAreCacheMisses() throws Exception {
        CacheFixture staleLeaf = fixture(true, 3);
        when(staleLeaf.operations().range(CACHE_KEY, -2, -1)).thenReturn(List.of(
                cacheEntry(staleLeaf.objectMapper(), "msg1", null, 1, "user", "question"),
                cacheEntry(staleLeaf.objectMapper(), "msg2", "msg1", 2, "assistant", "answer")));

        assertThat(staleLeaf.cache().findRecentMessages(
                "tenant1", "user1", "session1", "msg-other", 2)).isEmpty();

        CacheFixture brokenPath = fixture(true, 3);
        when(brokenPath.operations().range(CACHE_KEY, -2, -1)).thenReturn(List.of(
                cacheEntry(brokenPath.objectMapper(), "msg1", null, 1, "user", "question"),
                cacheEntry(brokenPath.objectMapper(), "msg2", "another-parent", 2, "assistant", "answer")));

        assertThat(brokenPath.cache().findRecentMessages(
                "tenant1", "user1", "session1", "msg2", 2)).isEmpty();
    }

    @Test
    void malformedAndIncompleteEntriesAreCacheMisses() throws Exception {
        CacheFixture malformed = fixture(true, 3);
        when(malformed.operations().range(CACHE_KEY, -1, -1)).thenReturn(List.of("not-json"));

        assertThat(malformed.cache().findRecentMessages(
                "tenant1", "user1", "session1", "msg1", 1)).isEmpty();

        CacheFixture incomplete = fixture(true, 3);
        when(incomplete.operations().range(CACHE_KEY, -2, -1)).thenReturn(List.of(
                cacheEntry(incomplete.objectMapper(), "msg2", "outside-window", 2, "assistant", "answer")));

        assertThat(incomplete.cache().findRecentMessages(
                "tenant1", "user1", "session1", "msg2", 2)).isEmpty();
    }

    private CacheFixture fixture(boolean enabled, int recentTurns) {
        return fixture(enabled, true, recentTurns);
    }

    private CacheFixture fixture(boolean enabled, boolean cacheEnabled, int recentTurns) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> operations = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(operations);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ShortTermMemoryRedisProperties properties = new ShortTermMemoryRedisProperties();
        properties.setEnabled(enabled);
        properties.setCacheEnabled(cacheEnabled);
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.getShortTerm().setCacheRecentTurns(recentTurns);
        RedisShortTermMemoryCache cache = new RedisShortTermMemoryCache(
                redis,
                objectMapper,
                properties,
                memoryProperties,
                FinanceExRedisKeyBuilder.ofEnv("test"));
        return new CacheFixture(cache, redis, operations, objectMapper, properties);
    }

    private String cacheEntry(ObjectMapper objectMapper,
                              String messageId,
                              String parentMessageId,
                              long nodeOrder,
                              String role,
                              String content) throws Exception {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("messageId", messageId);
        entry.put("parentMessageId", parentMessageId);
        entry.put("nodeOrder", nodeOrder);
        entry.put("runId", "run-" + nodeOrder);
        entry.put("role", role);
        entry.put("content", content);
        entry.put("memoryEligible", true);
        entry.put("createdAt", Instant.EPOCH.plusSeconds(nodeOrder));
        return objectMapper.writeValueAsString(entry);
    }

    private ChatMessage message(int index) {
        return new ChatMessage(
                "msg" + index,
                "tenant1",
                "user1",
                "session1",
                "user",
                "message " + index,
                null,
                Instant.EPOCH.plusSeconds(index));
    }

    private ChatMessage assistantMessage() {
        return new ChatMessage(
                "msg-assistant",
                "tenant1",
                "user1",
                "session1",
                "msg-user",
                2L,
                1,
                0,
                "assistant",
                "answer",
                null,
                "run-2",
                "NORMAL",
                false,
                null,
                null,
                null,
                null,
                "{\"skillId\":\"skill-a\",\"other\":true}",
                Instant.EPOCH.plusSeconds(2));
    }

    private String messageId(ObjectMapper objectMapper, String value) {
        try {
            return objectMapper.readTree(value).path("messageId").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("Test message deserialization failed", ex);
        }
    }

    private record CacheFixture(
            RedisShortTermMemoryCache cache,
            StringRedisTemplate redis,
            ListOperations<String, String> operations,
            ObjectMapper objectMapper,
            ShortTermMemoryRedisProperties properties
    ) {
    }
}
