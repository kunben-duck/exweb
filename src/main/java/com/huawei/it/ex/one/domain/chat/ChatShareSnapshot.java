package com.huawei.it.ex.one.domain.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 聊天分享展示快照。
 *
 * <p>单轮分享使用 question/answer/parts，多消息分享使用 messages。两种结构保存在同一个固定快照中，
 * 不包含 feedback、隐藏/debug parts 或任何鉴权凭据。</p>
 */
public record ChatShareSnapshot(
        ChatShareMessageSnapshot question,
        ChatShareMessageSnapshot answer,
        List<ChatShareSnapshotPart> parts,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ChatShareSelectedMessageSnapshot> messages,
        Instant createdAt
) {
    public ChatShareSnapshot {
        parts = parts == null ? List.of() : List.copyOf(parts);
        messages = messages == null ? List.of() : List.copyOf(messages);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * 保留旧单轮快照的构造方式，避免现有创建与测试调用方感知多消息扩展。
     */
    public ChatShareSnapshot(ChatShareMessageSnapshot question, ChatShareMessageSnapshot answer,
                             List<ChatShareSnapshotPart> parts, Instant createdAt) {
        this(question, answer, parts, List.of(), createdAt);
    }
}
