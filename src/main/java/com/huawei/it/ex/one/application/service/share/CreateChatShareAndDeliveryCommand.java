package com.huawei.it.ex.one.application.service.share;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * 创建分享并立即发送的便捷命令。
 *
 * @param messageId 被分享的 assistant 消息 ID。
 * @param title 分享标题或发送标题；为空时使用问题生成。
 * @param expiresAt 分享过期时间。
 * @param provider 发送 provider 编码。
 * @param targetAccounts 目标用户账号列表。
 * @param groupIds 目标群组 ID 列表。
 * @param content 发送正文覆盖值。
 * @param language 前端透传语言标识。
 * @param forwardHeaders 请求入口捕获到的敏感转发头快照，仅在出站 provider 调用中使用。
 */
public record CreateChatShareAndDeliveryCommand(
        String messageId,
        String title,
        Instant expiresAt,
        String provider,
        List<String> targetAccounts,
        List<String> groupIds,
        String content,
        String language,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public CreateChatShareAndDeliveryCommand {
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
