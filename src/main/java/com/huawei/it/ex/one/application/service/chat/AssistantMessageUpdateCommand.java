package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import java.util.List;

/**
 * 更新已有 assistant 消息的命令。
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param session 归属会话快照。
 * @param messageId 需要更新的 assistant 消息 ID。
 * @param content 最新 assistant 正文。
 * @param runId 产生本次续接输出的 run ID。
 * @param partDrafts 本次续接新增的 parts。
 * @param metadataJson 覆盖后的消息元数据 JSON；可为空以清理等待态标记。
 * @param appendAnswerPart 是否追加合成 ANSWER part。
 * @param preserveExistingProjection 是否保留原正文及metadata，仅追加本轮parts。
 */
public record AssistantMessageUpdateCommand(
        String tenantId,
        String userId,
        ChatSession session,
        String messageId,
        String content,
        String runId,
        List<ChatMessagePartDraft> partDrafts,
        String metadataJson,
        boolean appendAnswerPart,
        boolean preserveExistingProjection
) {
    public AssistantMessageUpdateCommand(String tenantId, String userId, ChatSession session, String messageId,
                                         String content, String runId, List<ChatMessagePartDraft> partDrafts,
                                         String metadataJson, boolean appendAnswerPart) {
        this(tenantId, userId, session, messageId, content, runId, partDrafts, metadataJson,
                appendAnswerPart, false);
    }

    public AssistantMessageUpdateCommand(String tenantId, String userId, ChatSession session, String messageId,
                                         String content, String runId, List<ChatMessagePartDraft> partDrafts,
                                         String metadataJson) {
        this(tenantId, userId, session, messageId, content, runId, partDrafts, metadataJson, true, false);
    }

    public List<ChatMessagePartDraft> safePartDrafts() {
        return partDrafts == null ? List.of() : partDrafts;
    }
}
