package com.huawei.it.ex.one.chat.domain;

import java.util.Optional;

/**
 * 聊天流式 topic 命名工具。
 *
 * <p>正式版 WebSocket 采用“用户级连接 + run 级 topic 订阅”。topic 是服务端生成的
 * 不透明逻辑通道标识，前端只能使用 {@code /chat/runs} 返回的 topic 订阅，不能自行传入用户身份。</p>
 */
public final class ChatStreamTopics {
    /** run 级流式 topic 前缀，后缀为 runId。 */
    public static final String RUN_TOPIC_PREFIX = "chat-run-";

    private ChatStreamTopics() {
    }

    /**
     * 根据 runId 生成前端可订阅的流式 topic。
     *
     * @param runId 本轮 run 标识。
     * @return run 级 stream topic。
     */
    public static String runTopic(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        return RUN_TOPIC_PREFIX + runId.trim();
    }

    /**
     * 从 run 级 topic 解析 runId。
     *
     * @param topicId 前端传入的 topicId。
     * @return 合法 run topic 中携带的 runId。
     */
    public static Optional<String> parseRunId(String topicId) {
        if (topicId == null || topicId.isBlank() || !topicId.startsWith(RUN_TOPIC_PREFIX)) {
            return Optional.empty();
        }
        String runId = topicId.substring(RUN_TOPIC_PREFIX.length()).trim();
        return runId.isBlank() ? Optional.empty() : Optional.of(runId);
    }
}
