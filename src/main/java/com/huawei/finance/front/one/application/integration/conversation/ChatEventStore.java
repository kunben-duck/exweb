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
     * 按用户归属查询指定会话在某个序号之后的事件。
     *
     * <p>事件补发接口必须直接在事实源查询中携带 owner 条件，不能只依赖上层先校验 session
     * 归属后再按裸 sessionId 查询；这样即使事件表存在异常数据，也不会跨租户或跨用户补发。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param afterSeq 已处理的最大事件序号。
     * @return 大于 afterSeq 的会话事件，按 seq 正序排列。
     */
    List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq);

    /**
     * 按用户归属查询指定 run 在某个序号之后的事件。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId run 所属前端聊天会话标识。
     * @param runId run 标识。
     * @param afterSeq 已处理的最大事件序号。
     * @return 大于 afterSeq 的 run 事件，按 seq 正序排列。
     */
    List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq);

    /**
     * 按用户归属查询会话当前最大事件序号。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前会话最大 seq；无事件时返回 0。
     */
    long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId);
}
