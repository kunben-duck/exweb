package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;

/**
 * 聊天事件事实源端口。
 *
 * <p>事件表既服务审计，也服务前端断线恢复。写入时必须返回带持久化 seq 的事件，
 * 这样实时输出、恢复输出和数据库中的顺序完全一致。</p>
 */
public interface ChatEventStore {
    /**
     * 追加事件并返回带持久化序号的事件。
     *
     * @param event 原始领域事件。
     * @return 带持久化 seq 的事件。
     */
    ChatEvent append(ChatEvent event);

    /**
     * 查询指定会话在某个序号之后的事件。
     *
     * @param sessionId 前端聊天会话标识。
     * @param afterSeq 已处理的最大事件序号。
     * @return 大于 afterSeq 的会话事件，按 seq 正序排列。
     */
    List<ChatEvent> findBySessionIdAndAfterSeq(String sessionId, long afterSeq);

    /**
     * 查询指定 run 在某个序号之后的事件。
     *
     * @param runId run 标识。
     * @param afterSeq 已处理的最大事件序号。
     * @return 大于 afterSeq 的 run 事件，按 seq 正序排列。
     */
    List<ChatEvent> findByRunIdAndAfterSeq(String runId, long afterSeq);

    /**
     * 查询会话当前最大事件序号。
     *
     * @param sessionId 前端聊天会话标识。
     * @return 当前会话最大 seq；无事件时返回 0。
     */
    long findLatestSeqBySessionId(String sessionId);
}
