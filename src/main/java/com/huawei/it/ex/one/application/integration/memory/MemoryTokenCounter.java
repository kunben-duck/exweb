/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.memory;

import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;

import java.util.List;

/**
 * 短期上下文 Token 计数防腐接口。
 *
 * <p>默认实现使用保守的 UTF-8 JSON 字节估算；后续接入真实 GLM tokenizer 时只替换实现。</p>
 */
public interface MemoryTokenCounter {
    /**
     * 计算完整消息数组占用的 Token 预算。
     *
     * @param messages 待发送的上下文消息。
     * @return 非负 Token 数。
     */
    int countTokens(List<ConversationMemoryMessage> messages);
}
