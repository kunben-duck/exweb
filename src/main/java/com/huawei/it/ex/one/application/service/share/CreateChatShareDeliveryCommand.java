package com.huawei.it.ex.one.application.service.share;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 分享发送命令。
 *
 * @param shareId 待发送分享 ID。
 * @param provider 发送 provider 编码。
 * @param targetAccounts 目标用户账号列表。
 * @param groupIds 目标群组 ID 列表。
 * @param title 发送标题覆盖值；为空时使用分享标题。
 * @param content 前端提供的发送正文；为空时发送空字符串，非空时转换为纯文本摘要。
 * @param language 前端透传语言标识。
 * @param forwardHeaders 请求入口捕获到的敏感转发头快照，仅在出站 provider 调用中使用。
 */
public record CreateChatShareDeliveryCommand(
        String shareId,
        String provider,
        List<String> targetAccounts,
        List<String> groupIds,
        String title,
        String content,
        String language,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public CreateChatShareDeliveryCommand {
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
