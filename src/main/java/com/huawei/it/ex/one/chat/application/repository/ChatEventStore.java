package com.huawei.it.ex.one.chat.application.repository;

import com.huawei.it.ex.one.chat.application.model.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.common.event.ChatEvent;
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
     * 在 execution 写入权保护下追加事件，并返回带持久化序号的事件。
     *
     * <p>这是高并发流式输出的生产写入路径。实现层必须在同一条数据库写入语句内校验
     * run/session/tenant/user 归属、业务 run 状态、execution owner 和 fencing token。
     * 条件不满足时应抛出 {@link ChatEventAppendRejectedException}，调用方据此停止当前后台流。</p>
     *
     * @param event 原始领域事件。
     * @param claim 后台执行流启动时获得的 execution 写入权声明。
     * @return 带持久化 seq 的事件。
     */
    ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim);

    /**
     * 在同一个 execution 写入权保护下批量追加同一 run 的普通运行事件。
     *
     * <p>默认实现保持兼容，逐条调用单事件方法；生产数据库实现应覆盖该方法，使用一个短事务
     * 完成一次 run 栅栏、一次序号分配和一次批量写入。</p>
     *
     * @param events 同一 run、同一 session 的有序事件。
     * @param claim 当前 execution 写入权声明。
     * @return 按输入顺序返回带持久化 seq 的事件。
     */
    default List<ChatEvent> appendBatchWithExecutionGuard(List<ChatEvent> events, RunExecutionClaim claim) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream().map(event -> appendWithExecutionGuard(event, claim)).toList();
    }

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
