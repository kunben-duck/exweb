/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 下游可消费的短期对话上下文消息。
 *
 * @param role 消息角色，仅允许 user 或 assistant。
 * @param content 消息正文。
 * @param skillId 当前消息路径对应的Runtime调用标识；未知时不发送。
 */
public record ConversationMemoryMessage(
        String role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) String skillId
) {
    public ConversationMemoryMessage(String role, String content) {
        this(role, content, null);
    }
}
