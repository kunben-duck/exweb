package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;
import java.util.Map;

/**
 * 前端重新生成回答请求。
 *
 * <p>message 为空时，服务端会复用原 run 所属会话最近一条用户消息；传入 message 时表示基于同一
 * session 发起一次新的修订提问。retry 仍会创建新的 runId，不覆盖旧 run 事件。</p>
 *
 * @param commandId 前端命令标识，用于排障或幂等。
 * @param conversationId 前端对话标识，可为空。
 * @param message 可选的新用户输入。
 * @param attachments 本轮重试关联的附件。
 * @param metadata 前端扩展元数据。
 */
public record RetryChatRunRequest(
        String commandId,
        String conversationId,
        String message,
        List<ChatAttachmentDto> attachments,
        Map<String, Object> metadata
) {}
