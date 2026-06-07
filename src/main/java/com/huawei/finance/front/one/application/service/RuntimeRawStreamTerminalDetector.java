package com.huawei.finance.front.one.application.service;

/**
 * Runtime 原始流终态标记识别器。
 *
 * <p>Relay 可能返回标准 {@code stream-complete}，也可能返回历史拼写
 * {@code steam-complete} 或 SSE {@code data: [DONE]}。该工具只用于 raw log 旁路的
 * terminal 标记，不决定 ChatService run 生命周期；真正的前端事件仍由 Runtime normalizer
 * 转成 {@code message.completed/run.completed} 后闭合。</p>
 */
final class RuntimeRawStreamTerminalDetector {
    private RuntimeRawStreamTerminalDetector() {
    }

    static boolean isTerminalChunk(String chunk) {
        if (chunk == null) {
            return false;
        }
        String normalized = chunk.trim().toLowerCase();
        return normalized.equals("[done]")
                || normalized.equals("steam-complete")
                || normalized.equals("stream-complete")
                || normalized.equals("stream_complete")
                || normalized.equals("stream.complete")
                || normalized.equals("stream-completed")
                || normalized.contains("data: [done]")
                || normalized.contains("steam-complete")
                || normalized.contains("stream-complete");
    }
}
