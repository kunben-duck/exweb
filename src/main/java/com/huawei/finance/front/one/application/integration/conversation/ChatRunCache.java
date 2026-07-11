package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunCancelSignal;
import java.util.Optional;

/**
 * ChatRun Redis 热缓存端口。
 *
 * <p>active run 缓存用于页面初始化快速判断是否仍在输出；cancel flag 用于跨 JVM
 * 阻断取消后的后续事件写入。</p>
 */
public interface ChatRunCache {
    /**
     * 读取会话当前 active run。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return Redis 中的 active run；不存在或过期时为空。
     */
    Optional<ChatRun> getActive(String tenantId, String userId, String sessionId);

    /**
     * 尝试声明某会话当前 active run。
     *
     * <p>该方法仅保留给兼容调用；active run 唯一性由数据库部分唯一索引保证，Redis
     * 不再承担准入正确性。</p>
     *
     * @param run 准备进入 RUNNING 的 run 快照。
     * @return true 表示当前实例成功声明 active run；false 表示已有其他 active run。
     */
    @Deprecated(forRemoval = false)
    boolean tryClaimActive(ChatRun run);

    /**
     * 写入或刷新会话当前 active run。
     *
     * @param run 需要缓存的 active run 快照。
     */
    void putActive(ChatRun run);

    /**
     * 删除会话 active run 缓存。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     */
    void evictActive(String tenantId, String userId, String sessionId);

    /**
     * 设置 run 取消标记。
     *
     * @param runId 需要取消的 run 标识。
     */
    void markCancellationRequested(String runId);

    /**
     * 读取 run 的取消信号。
     *
     * @param runId run 标识。
     * @return Redis 对该 run 是否已取消的判断；Redis 不可用时返回 UNKNOWN。
     */
    ChatRunCancelSignal cancellationSignal(String runId);
}
