package com.huawei.it.ex.one.domain.memory;

/**
 * 下游可消费的短期对话上下文消息。
 *
 * @param role 消息角色，仅允许 user 或 assistant。
 * @param content 消息正文。
 */
public record ConversationMemoryMessage(
        String role,
        String content
) {
}
