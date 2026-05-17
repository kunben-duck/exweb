package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatRun;
import java.util.Optional;

/**
 * ChatRun 事实源仓储端口。
 *
 * <p>openGauss 是 run 生命周期状态的最终事实源；Redis 只能保存 active run 和取消标记。</p>
 */
public interface ChatRunRepository {
    /**
     * 保存 run 快照。
     *
     * @param run run 生命周期快照。
     * @return 已保存的 run。
     */
    ChatRun save(ChatRun run);

    /**
     * 按 runId 查询 run。
     *
     * @param runId run 标识。
     * @return run 快照；不存在时为空。
     */
    Optional<ChatRun> findById(String runId);

    /**
     * 按用户归属查询 run，用于 stop 权限校验。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param runId run 标识。
     * @return 当前用户拥有的 run；不存在或不属于当前用户时为空。
     */
    Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId);

    /**
     * 查询会话当前仍在运行或取消中的 run。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前 active run；不存在时为空。
     */
    Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId);
}
