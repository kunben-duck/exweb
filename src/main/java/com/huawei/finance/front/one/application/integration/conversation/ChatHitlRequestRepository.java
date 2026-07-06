package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatHitlRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * HITL 等待用户输入请求仓储。
 */
public interface ChatHitlRequestRepository {
    /**
     * 保存新等待请求。
     *
     * @param request 等待请求快照。
     * @return 已保存请求。
     */
    ChatHitlRequest insert(ChatHitlRequest request);

    /**
     * 按 owner 和 ID 查询等待请求。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param hitlRequestId HITL 请求 ID。
     * @return 当前用户拥有的请求。
     */
    Optional<ChatHitlRequest> findByOwnerAndId(String tenantId, String userId, String hitlRequestId);

    /**
     * 查询指定会话当前可提交的等待请求。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 当前 WAITING 请求。
     */
    Optional<ChatHitlRequest> findWaitingBySession(String tenantId, String userId, String sessionId);

    /**
     * 原子声明用户提交，避免多页签重复提交同一个等待请求。
     *
     * @param command 原子 claim 命令。
     * @return true 表示成功从 WAITING 切换为 RESPONDING。
     */
    boolean claimForResponse(ChatHitlClaimCommand command);

    /**
     * 标记续接成功。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param hitlRequestId HITL 请求 ID。
     * @param answeredAt 完成时间。
     * @return 影响行数。
     */
    int markAnswered(String tenantId, String userId, String hitlRequestId, Instant answeredAt);

    /**
     * 续接失败时把请求退回 WAITING，允许用户重试。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param hitlRequestId HITL 请求 ID。
     * @return 影响行数。
     */
    int markWaiting(String tenantId, String userId, String hitlRequestId);

    /**
     * 取消某会话下仍在等待或响应中的 HITL 请求。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param cancelledAt 取消时间。
     * @return 影响行数。
     */
    int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt);

    /**
     * 标记等待请求过期。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param hitlRequestId HITL 请求 ID。
     * @return 影响行数。
     */
    int markExpired(String tenantId, String userId, String hitlRequestId);

    /**
     * 原子 claim 参数。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param hitlRequestId HITL 请求 ID。
     * @param continueRunId 续接 run ID。
     * @param responsePayload 用户提交的回答 payload。
     * @param now claim 时间。
     */
    record ChatHitlClaimCommand(
            String tenantId,
            String userId,
            String hitlRequestId,
            String continueRunId,
            Map<String, Object> responsePayload,
            Instant now
    ) {
    }
}
