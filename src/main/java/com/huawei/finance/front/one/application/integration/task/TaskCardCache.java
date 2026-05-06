package com.huawei.finance.front.one.application.integration.task;

import com.huawei.finance.front.one.domain.task.TaskCard;
import java.util.Optional;

/**
 * TaskCard Redis 热缓存端口。
 */
public interface TaskCardCache {
    /**
     * 读取当前会话 active task。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return active task，不存在则为空。
     */
    Optional<TaskCard> getActive(String tenantId, String userId, String sessionId);

    /**
     * 写入 active task 和 task card 快照。
     *
     * @param taskCard 任务卡片。
     */
    void put(TaskCard taskCard);

    /**
     * 删除当前会话 active task 热缓存。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     */
    void evictActive(String tenantId, String userId, String sessionId);
}
