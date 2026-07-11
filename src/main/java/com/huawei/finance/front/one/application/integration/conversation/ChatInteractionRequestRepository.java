package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatInteractionRequest;
import java.time.Instant;
import java.util.List;
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
     * 仅当 Interaction 仍由指定 continuation run 持有时退回 WAITING。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @param continueRunId 当前持有响应 claim 的续接 run ID。
     * @return 影响行数。
     */
    int markWaitingForRun(String tenantId, String userId, String interactionId, String continueRunId);

    /**
     * 查询 continue run 已经失败或取消、但仍持有 RESPONDING claim 的 Interaction。
     *
     * @param limit 最大候选数量。
     * @return 按更新时间正序排列的孤儿 claim 候选。
     */
    default List<ChatInteractionRequest> findRespondingWithTerminalContinuation(int limit) {
        return List.of();
    }

    /**
     * 查询需要 watchdog 对账的 RESPONDING continuation。
     */
    default List<ContinuationReconcileCandidate> findRespondingReconcileCandidates(Instant orphanBefore, int limit) {
        return findRespondingWithTerminalContinuation(limit).stream()
                .map(request -> new ContinuationReconcileCandidate(
                        request, ContinuationReconcileState.TERMINAL_RUN, orphanBefore))
                .toList();
    }

    /**
     * 原子复核并释放“终态 run 或超过宽限期仍不存在 run”的 Interaction claim。
     */
    default int markWaitingIfContinuationOrphaned(String tenantId, String userId, String interactionId,
                                                   String continueRunId, Instant orphanBefore) {
        return markWaitingForRun(tenantId, userId, interactionId, continueRunId);
    }

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

    enum ContinuationReconcileState {
        TERMINAL_RUN,
        MISSING_RUN,
        MISSING_EXECUTION
    }

    record ContinuationReconcileCandidate(
            ChatInteractionRequest request,
            ContinuationReconcileState state,
            Instant orphanBefore
    ) {
    }
}
