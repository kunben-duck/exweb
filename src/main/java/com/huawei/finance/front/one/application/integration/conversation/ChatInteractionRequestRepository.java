package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatInteractionRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Interaction 等待用户输入请求仓储。
 */
public interface ChatInteractionRequestRepository {
    /**
     * 保存新等待请求。
     *
     * @param request 等待请求快照。
     * @return 已保存请求。
     */
    ChatInteractionRequest insert(ChatInteractionRequest request);

    /**
     * 按 owner 和 ID 查询等待请求。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @return 当前用户拥有的请求。
     */
    Optional<ChatInteractionRequest> findByOwnerAndId(String tenantId, String userId, String interactionId);

    /**
     * 查询指定会话当前可提交的等待请求。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 当前 WAITING 请求。
     */
    Optional<ChatInteractionRequest> findWaitingBySession(String tenantId, String userId, String sessionId);

    /**
     * 原子声明用户提交，避免多页签重复提交同一个等待请求。
     *
     * @param command 原子 claim 命令。
     * @return true 表示成功从 WAITING 切换为 RESPONDING。
     */
    boolean claimInteractionResponse(ChatInteractionClaimCommand command);

    /**
     * 标记续接成功。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @param answeredAt 完成时间。
     * @return 影响行数。
     */
    int markAnswered(String tenantId, String userId, String interactionId, Instant answeredAt);

    /**
     * 续接失败时把请求退回 WAITING，允许用户重试。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @return 影响行数。
     */
    int markWaiting(String tenantId, String userId, String interactionId);

    /**
     * 取消某会话下仍在等待或响应中的 Interaction 请求。
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
     * @param interactionId Interaction 请求 ID。
     * @return 影响行数。
     */
    int markExpired(String tenantId, String userId, String interactionId);

    /**
     * 原子 claim 参数。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @param continueRunId 续接 run ID。
     * @param responsePayload 用户提交的回答 payload。
     * @param now claim 时间。
     */
    record ChatInteractionClaimCommand(
            String tenantId,
            String userId,
            String interactionId,
            String continueRunId,
            Map<String, Object> responsePayload,
            Instant now
    ) {
    }
}
