package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

/**
 * 异步聊天 run 创建结果。
 *
 * <p>前端先创建 run，服务端后台继续执行 Runtime；浏览器随后通过 WebSocket 订阅实时事件，
 * 或通过 SSE 按 sessionId 和 lastSeq 补发缺失事件。这样页面刷新或关闭后，运行不会依赖原始 HTTP 连接。</p>
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param firstSeq run.started 的持久化序号。
 * @param createdAt run.started 创建时间。
 * @param streamTopicId 本轮回答的 WebSocket run 级订阅 topic。
 */
public record ChatRunStartResult(
        String runId,
        String sessionId,
        long firstSeq,
        Instant createdAt,
        String streamTopicId
) {}
