package com.huawei.it.ex.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

class Utf8JsonMemoryTokenCounterTest {
    @Test
    void countsSerializedUtf8BytesForChineseAndEmoji() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ConversationMemoryMessage> messages = List.of(
                new ConversationMemoryMessage("user", "中文😀"));
        Utf8JsonMemoryTokenCounter counter = new Utf8JsonMemoryTokenCounter(objectMapper);

        assertThat(counter.countTokens(messages))
                .isEqualTo(objectMapper.writeValueAsBytes(messages).length);
    }

    @Test
    void skillIdIsIncludedInSerializedTokenBudget() {
        ObjectMapper objectMapper = new ObjectMapper();
        Utf8JsonMemoryTokenCounter counter = new Utf8JsonMemoryTokenCounter(objectMapper);

        int withoutSkill = counter.countTokens(List.of(
                new ConversationMemoryMessage("assistant", "answer")));
        int withSkill = counter.countTokens(List.of(
                new ConversationMemoryMessage("assistant", "answer", "skill-a")));

        assertThat(withSkill).isGreaterThan(withoutSkill);
    }
}
