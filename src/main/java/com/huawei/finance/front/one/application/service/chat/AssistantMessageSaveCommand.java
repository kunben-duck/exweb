package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.chat.ChatMessagePartDraft;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.util.List;

/**
 * 保存 assistant 完整消息的命令。
 *
 * <p>正常完成时保存最终 assistant；用户主动 stop 且已有正文或用户可见过程 parts 时，也用同一
 * 命令保存 partial assistant。failed/watchdog 场景不应构造该命令保存半截失败噪声。</p>
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param session 归属会话快照。
 * @param content 最终正文；snapshot 存在时应优先使用 snapshot 内容。
 * @param runId 产生该 assistant 的 run ID。
 * @param parentMessageId assistant 在消息树中的父 user message ID。
 * @param regeneratedFromMessageId 重新生成来源 assistant，可为空。
 * @param partDrafts thinking/tool/card/reference 等过程信息草稿。
 * @param metadataJson 消息级元数据 JSON，例如用户 stop 固化 partial assistant 标记。
 * @param messageId 可选的预分配 assistant 消息 ID；用于在 run.completed 事件入库前确定反馈目标。
 */
public record AssistantMessageSaveCommand(
        String tenantId,
        String userId,
        ChatSession session,
        String content,
        String runId,
        String parentMessageId,
        String regeneratedFromMessageId,
        List<ChatMessagePartDraft> partDrafts,
        String metadataJson,
        String messageId
) {
    public AssistantMessageSaveCommand(String tenantId, String userId, ChatSession session, String content,
                                       String runId, String parentMessageId, String regeneratedFromMessageId,
                                       List<ChatMessagePartDraft> partDrafts, String metadataJson) {
        this(tenantId, userId, session, content, runId, parentMessageId, regeneratedFromMessageId,
                partDrafts, metadataJson, null);
    }

    public List<ChatMessagePartDraft> safePartDrafts() {
        return partDrafts == null ? List.of() : partDrafts;
    }

    public String normalizedMessageId() {
        return messageId == null || messageId.isBlank() ? null : messageId.trim();
    }
}
