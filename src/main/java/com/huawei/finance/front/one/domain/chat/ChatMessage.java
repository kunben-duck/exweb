package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.List;

/**
 * 聊天消息树节点。
 *
 * <p>ChatService 当前只把用户可见的 user/assistant 完整消息写入本表。
 * 流式 delta、run.started、run.cancelled 等传输事实仍保存在事件表中。</p>
 *
 * @param id 消息唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 前端聊天会话标识。
 * @param parentMessageId 消息树父节点；为空表示当前会话的根可见消息。
 * @param nodeOrder 当前会话内节点创建序号，用于稳定排序和快照复制。
 * @param treeDepth 消息在树中的深度，根可见消息为 0。
 * @param siblingIndex 同一父节点下同角色候选序号，用于前端显示 1/N、2/N。
 * @param role 消息角色，例如 user 或 assistant。
 * @param content 消息正文。
 * @param tokenCount 消息 token 数估算值，可为空。
 * @param runId 产生该消息的 runId；分支快照消息为空。
 * @param originType 消息来源类型，例如 NORMAL 或 BRANCH_SNAPSHOT。
 * @param locked 是否只读；分支历史快照消息必须为 true。
 * @param sourceSessionId 分支快照来源会话；普通消息为空。
 * @param sourceMessageId 分支快照来源消息；普通消息为空。
 * @param editedFromMessageId 编辑历史 user 消息时，新 user 消息来源。
 * @param regeneratedFromMessageId 重新生成 assistant 回复时，新 assistant 消息来源。
 * @param metadataJson 消息扩展元数据 JSON。
 * @param parts assistant 消息的结构化过程信息；user 消息通常为空。
 * @param createdAt 消息创建时间。
 */
public record ChatMessage(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String parentMessageId,
        Long nodeOrder,
        Integer treeDepth,
        Integer siblingIndex,
        String role,
        String content,
        Integer tokenCount,
        String runId,
        String originType,
        boolean locked,
        String sourceSessionId,
        String sourceMessageId,
        String editedFromMessageId,
        String regeneratedFromMessageId,
        String metadataJson,
        List<ChatMessagePart> parts,
        Instant createdAt
) {
    public ChatMessage(String id, String tenantId, String userId, String sessionId, String parentMessageId,
                       Long nodeOrder, Integer treeDepth, Integer siblingIndex, String role, String content,
                       Integer tokenCount, String runId, String originType, boolean locked, String sourceSessionId,
                       String sourceMessageId, String editedFromMessageId, String regeneratedFromMessageId,
                       String metadataJson, Instant createdAt) {
        this(id, tenantId, userId, sessionId, parentMessageId, nodeOrder, treeDepth, siblingIndex, role, content,
                tokenCount, runId, originType, locked, sourceSessionId, sourceMessageId, editedFromMessageId,
                regeneratedFromMessageId, metadataJson, List.of(), createdAt);
    }

    /**
     * 兼容普通线性消息写入的便捷构造器；新代码应显式传入消息树字段。
     */
    public ChatMessage(String id, String tenantId, String userId, String sessionId, String role, String content,
                       Integer tokenCount, Instant createdAt) {
        this(id, tenantId, userId, sessionId, null, 0L, 0, 0, role, content, tokenCount,
                null, "NORMAL", false, null, null, null, null, null, createdAt);
    }

    public ChatMessage {
        originType = originType == null || originType.isBlank() ? "NORMAL" : originType;
        treeDepth = treeDepth == null ? 0 : treeDepth;
        siblingIndex = siblingIndex == null ? 0 : siblingIndex;
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /**
     * @return true 表示该消息来自分支历史快照，不能被编辑、删除或重新生成。
     */
    public boolean branchSnapshot() {
        return locked || "BRANCH_SNAPSHOT".equals(originType);
    }

    /**
     * 返回携带指定 message parts 的消息副本。
     *
     * @param nextParts 新的结构化过程信息。
     * @return 带 parts 的不可变消息副本。
     */
    public ChatMessage withParts(List<ChatMessagePart> nextParts) {
        return new ChatMessage(id, tenantId, userId, sessionId, parentMessageId, nodeOrder, treeDepth,
                siblingIndex, role, content, tokenCount, runId, originType, locked, sourceSessionId,
                sourceMessageId, editedFromMessageId, regeneratedFromMessageId, metadataJson, nextParts, createdAt);
    }
}
