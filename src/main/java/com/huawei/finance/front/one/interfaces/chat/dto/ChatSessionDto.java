package com.huawei.finance.front.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 前端聊天会话 DTO。
 *
 * @param sessionId 前端聊天会话标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param title 会话标题。
 * @param status 会话状态。
 * @param channel 会话来源渠道。
 * @param appId 会话所属应用标识。
 * @param appName 会话所属应用名称快照。
 * @param currentLeafMessageId 当前会话激活路径叶子消息。
 * @param rootSessionId 分支族根会话。
 * @param branchSourceSessionId 分支来源会话。
 * @param branchSourceMessageId 分支来源消息。
 * @param firstAssistantAnswer 会话第一条 assistant 完整回答；列表页用于展示首轮回答摘要，非列表场景可为空。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 */
public record ChatSessionDto(
        String sessionId,
        String tenantId,
        String userId,
        String title,
        String status,
        String channel,
        String appId,
        String appName,
        String currentLeafMessageId,
        String rootSessionId,
        String branchSourceSessionId,
        String branchSourceMessageId,
        String firstAssistantAnswer,
        Instant createdAt,
        Instant updatedAt
) {}
