package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import java.util.Optional;

/**
 * 聊天事件消费游标 openGauss 事实源端口。
 *
 * <p>该仓储保存用户在会话中的最大已消费事件序号，用于展示消费进度、辅助诊断和
 * 非 active 场景减少重复事件。恢复正在输出的 active run 时，前端仍应优先从 run
 * 首个事件之前补发，不能把 read cursor 当作新渲染实例的真实展示位置。写入必须
 * 保持单调递增，不能用较小 ack 覆盖较大游标。</p>
 */
public interface ChatReadCursorRepository {
    /**
     * 查询用户在指定会话中的消费游标。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 持久化游标；不存在时为空。
     */
    Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId);

    /**
     * 单调保存用户在指定会话中的最大已消费事件序号。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param lastConsumedSeq 用户已经处理完成的最大事件序号。
     * @return 保存后的游标快照。
     */
    ChatReadCursor upsert(String tenantId, String userId, String sessionId, long lastConsumedSeq);
}
