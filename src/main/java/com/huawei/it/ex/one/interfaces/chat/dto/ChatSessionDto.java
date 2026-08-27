package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 前端聊天会话 DTO。
 *
 * @param sessionId 前端聊天会话标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param title 会话标题。
 * @param status 会话状态。
 * @param lastRunStatus 最后创建的run状态；仅两个会话列表接口保证装配。
 * @param lastRunSkillId 最后创建的run最终Runtime调用标识；仅页码会话列表保证装配。
 * @param channel 会话来源渠道。
 * @param appId 会话所属应用标识。
 * @param appName 会话所属应用名称快照。
 * @param currentLeafMessageId 当前会话激活路径叶子消息。
 * @param rootSessionId 分支族根会话。
 * @param branchSourceSessionId 分支来源会话。
 * @param branchSourceMessageId 分支来源消息。
 * @param hasUnread 当前会话是否存在尚未确认展示的 assistant 消息。
 * @param latestMessageSeq 当前会话最新可见 assistant 消息事件水位。
 * @param lastReadSeq 当前用户已确认展示的消息事件水位。
 * @param firstAssistantAnswer 会话第一条 assistant 完整回答；列表页用于展示首轮回答摘要，非列表场景可为空。
 * @param firstAssistantMetadataJson 首条 assistant 消息的原始 metadata JSON；非列表场景可为空。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 */
public record ChatSessionDto(
        String sessionId,
        String tenantId,
        String userId,
        String title,
        String status,
        String lastRunStatus,
        String lastRunSkillId,
        String channel,
        String appId,
        String appName,
        String currentLeafMessageId,
        String rootSessionId,
        String branchSourceSessionId,
        String branchSourceMessageId,
        boolean hasUnread,
        long latestMessageSeq,
        long lastReadSeq,
        String firstAssistantAnswer,
        String firstAssistantMetadataJson,
        Instant createdAt,
        Instant updatedAt
) {}
