/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import java.util.List;
import java.util.Map;

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
 * @param metadata 经过请求边界清理的用户消息元数据。
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
        Map<String, Object> metadata,
        List<AttachmentRef> attachments
) {
    Map<String, Object> safeMetadata() {
        return metadata == null ? Map.of() : metadata;
    }

    List<AttachmentRef> safeAttachments() {
        return attachments == null ? List.of() : attachments;
    }
}
