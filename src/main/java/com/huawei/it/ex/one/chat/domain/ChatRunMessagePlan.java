package com.huawei.it.ex.one.chat.domain;

/**
 * 创建 run 前解析出的消息树写入计划。
 *
 * @param runMode 本轮 run 模式。
 * @param parentMessageId 本轮可见消息挂接的父节点。
 * @param userMessage 本轮用户消息；重新生成回答时为被重新生成回答的父 user 消息。
 * @param regeneratedFromMessageId 重新生成时来源 assistant 消息 ID。
 */
public record ChatRunMessagePlan(
        ChatRunMode runMode,
        String parentMessageId,
        ChatMessage userMessage,
        String regeneratedFromMessageId
) {}
