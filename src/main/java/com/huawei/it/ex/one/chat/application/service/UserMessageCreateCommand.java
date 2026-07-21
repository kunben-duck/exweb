package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatRunMode;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import java.util.List;

/**
 * 创建用户消息节点的命令。
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param session 归属会话快照。
 * @param content 用户本轮输入文本。
 * @param parentMessageId 消息树父节点；NEXT 不传时使用当前 leaf。
 * @param mode 本轮消息树模式。
 * @param runId 关联 run ID。
 * @param editedFromMessageId 编辑历史用户问题来源节点。
 * @param regeneratedFromMessageId 重新生成 assistant 来源节点。
 * @param attachments 本轮用户消息展示用附件引用。
 */
record UserMessageCreateCommand(
        String tenantId,
        String userId,
        ChatSession session,
        String content,
        String parentMessageId,
        ChatRunMode mode,
        String runId,
        String editedFromMessageId,
        String regeneratedFromMessageId,
        List<AttachmentRef> attachments
) {
    List<AttachmentRef> safeAttachments() {
        return attachments == null ? List.of() : attachments;
    }
}
