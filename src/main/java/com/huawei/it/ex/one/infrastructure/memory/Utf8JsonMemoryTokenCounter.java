/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.memory;

import com.huawei.it.ex.one.application.integration.memory.MemoryTokenCounter;
import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.util.List;

/** 使用序列化后 UTF-8 字节数作为保守 Token 估算。 */
@Component
public class Utf8JsonMemoryTokenCounter implements MemoryTokenCounter {
    private final ObjectMapper objectMapper;

    public Utf8JsonMemoryTokenCounter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public int countTokens(List<ConversationMemoryMessage> messages) {
        try {
            return objectMapper.writeValueAsBytes(messages == null ? List.of() : messages).length;
        } catch (JsonProcessingException ex) {
            return Integer.MAX_VALUE;
        }
    }
}
