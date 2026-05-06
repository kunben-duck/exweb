package com.huawei.finance.front.one.application.integration.task;

import com.huawei.finance.front.one.domain.task.TaskCard;
import java.util.Optional;

/**
 * TaskCard 持久化仓储端口。
 *
 * <p>openGauss 实现是任务状态事实源；Redis 只能作为热缓存。</p>
 */
public interface TaskCardRepository {
    /**
     * 查询当前会话仍可续接的 active task。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return active task，不存在则为空。
     */
    Optional<TaskCard> findActive(String tenantId, String userId, String sessionId);

    /**
     * 按任务 ID 查询任务卡片。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param taskId 任务标识。
     * @return 任务卡片，不存在则为空。
     */
    Optional<TaskCard> findByTaskId(String tenantId, String userId, String sessionId, String taskId);

    /**
     * 保存任务卡片。
     *
     * @param taskCard 任务卡片。
     * @return 保存后的任务卡片。
     */
    TaskCard save(TaskCard taskCard);
}
