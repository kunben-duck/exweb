package com.huawei.finance.front.one.domain.chat;

import java.util.List;
import java.util.Map;

/**
 * 聊天用例的统一输入命令。
 *
 * <p>正式版只有一个提问入口，接口层会把 {@code /chat/runs} 请求转换成该命令。
 * WebSocket 只负责订阅当前页面新建 run 的后台输出；SSE 负责恢复链路，其中会话级 SSE
 * 做有限补发，run 级 SSE 可接续 active run 到终态。因此命令不再携带传输协议、
 * 前端消息类型或前端响应模式。</p>
 *
 * @param commandId 前端或调用方生成的命令标识，用于幂等和排障。
 * @param tenantId 租户标识；进入 application 后会被服务端身份上下文覆盖。
 * @param userId 用户标识；进入 application 后会被服务端身份上下文覆盖。
 * @param sessionId 前端聊天会话标识。
 * @param conversationId 前端对话标识，通常与 sessionId 一致或为空。
 * @param channel 请求来源渠道，当前正式版固定为 web，保留用于会话审计。
 * @param message 本轮用户输入文本。
 * @param attachments 本轮关联附件引用。
 * @param metadata 前端或上游传入的扩展元数据。
 * @param runMode 本轮消息树写入模式。
 * @param parentMessageId 普通继续提问时显式指定的父节点；为空时使用会话 current leaf。
 * @param editedMessageId 编辑历史 user 消息时被编辑的原消息。
 * @param regeneratedMessageId 重新生成 assistant 回复时被重新生成的原回答。
 */
public record ChatCommand(
        String commandId,
        String tenantId,
        String userId,
        String sessionId,
        String conversationId,
        String channel,
        String message,
        List<AttachmentRef> attachments,
        Map<String, Object> metadata,
        ChatRunMode runMode,
        String parentMessageId,
        String editedMessageId,
        String regeneratedMessageId
) {
    /**
     * 兼容普通继续提问的便捷构造器。
     */
    public ChatCommand(String commandId, String tenantId, String userId, String sessionId, String conversationId,
                       String channel, String message, List<AttachmentRef> attachments, Map<String, Object> metadata) {
        this(commandId, tenantId, userId, sessionId, conversationId, channel, message, attachments, metadata,
                ChatRunMode.NEXT, null, null, null);
    }

    public ChatCommand {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        runMode = runMode == null ? ChatRunMode.NEXT : runMode;
    }
}
