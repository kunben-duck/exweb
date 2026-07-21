package com.huawei.it.ex.one.common.event;

import java.time.Instant;
import java.util.Map;

/**
 * assistant 最终回答快照事件。
 *
 * <p>该事件表示下游 Runtime 已给出完整回答正文。前端收到后应使用 {@code payload.content}
 * 替换当前草稿，而不是像 {@code message.delta} 一样追加。历史消息保存时也优先使用该快照。</p>
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
 * @param createdAt 事件创建时间。
 * @param content 完整回答正文。
 * @param payload 前端事件载荷，至少包含 content 字段。
 */
public record MessageSnapshotEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        String content,
        Map<String, Object> payload
) implements ChatEvent {
    public MessageSnapshotEvent {
        payload = ChatPayloadMaps.immutableCopy(payload);
    }

    public static MessageSnapshotEvent of(String runId, String sessionId, String content) {
        return new MessageSnapshotEvent(runId, sessionId, 0, Instant.now(), content,
                Map.of("content", content == null ? "" : content));
    }

    @Override
    public String type() {
        return "message.snapshot";
    }
}
