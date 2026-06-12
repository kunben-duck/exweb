package com.huawei.finance.front.one.domain.chat;

import java.util.Map;

/**
 * run 执行期间暂存的 message part 草稿。
 *
 * <p>草稿还没有 messageId 和 partId。只有 run.completed 且 assistant 正式落库后，
 * SessionApplicationService 才会把草稿补齐归属字段并写入 message part 表。</p>
 *
 * @param partType part 类型，例如 ANSWER、THINKING、TOOL、PROGRESS、CARD。
 * @param sourceType 下游原始事件类型。
 * @param contentText 可展示文本摘要。
 * @param title 前端展示标题。
 * @param status 展示状态。
 * @param channel 展示频道。
 * @param displayHint 展示建议。
 * @param visible 是否默认展示。
 * @param payload 标准化后的前端 payload。
 */
public record ChatMessagePartDraft(
        String partType,
        String sourceType,
        String contentText,
        String title,
        String status,
        String channel,
        String displayHint,
        Boolean visible,
        Map<String, Object> payload
) {
    public ChatMessagePartDraft(String partType, String sourceType, String contentText, Map<String, Object> payload) {
        this(partType, sourceType, contentText, null, null, null, null, null, payload);
    }

    public ChatMessagePartDraft {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
