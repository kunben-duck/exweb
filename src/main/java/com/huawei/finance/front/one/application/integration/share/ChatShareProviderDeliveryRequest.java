package com.huawei.finance.front.one.application.integration.share;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;

/**
 * 分享 provider 发送请求。
 *
 * @param tenantId 发起分享的用户租户。
 * @param userAccount 发起分享的用户账号。
 * @param title 分享卡片标题。
 * @param linkUrl 分享页面完整 URL。
 * @param content 分享卡片正文摘要。
 * @param targetAccount 目标用户账号，多个账号用英文逗号分隔。
 * @param groupId 目标群组 ID，多个群组用英文逗号分隔。
 * @param language 前端透传语言标识。
 * @param forwardHeaders 请求入口捕获到的敏感转发头快照，不进入 provider 请求体和发送记录。
 */
public record ChatShareProviderDeliveryRequest(
        String tenantId,
        String userAccount,
        String title,
        String linkUrl,
        String content,
        String targetAccount,
        String groupId,
        String language,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public ChatShareProviderDeliveryRequest {
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
