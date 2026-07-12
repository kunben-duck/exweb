package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_chat_interaction_request_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatInteractionRequestMapper {
    /**
     * 创建 Interaction 等待请求。
     *
     * @param row 等待请求写入行，包含归属、runtime 续接信息、请求 payload 和过期时间。
     * @return 影响行数。
     */
    int insert(ChatInteractionRequestRow row);

    /**
     * 按 owner 和请求 ID 查询 Interaction 请求。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @return 请求行；不存在时为 {@code null}。
     */
    ChatInteractionRequestRow findByOwnerAndId(@Param("tenantId") String tenantId,
                                        @Param("userId") String userId,
                                        @Param("interactionId") String interactionId);

    /**
     * 查询会话当前等待用户输入的请求。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return WAITING 请求行；不存在时为 {@code null}。
     */
    ChatInteractionRequestRow findWaitingBySession(@Param("tenantId") String tenantId,
                                            @Param("userId") String userId,
                                            @Param("sessionId") String sessionId);

    /**
     * 原子声明用户响应，只有 WAITING 状态会成功切换到 RESPONDING。
     *
     * @param row claim 更新行，包含 owner、interactionId、continueRunId 和响应 payload。
     * @return 影响行数。
     */
    int claimInteractionResponse(ChatInteractionClaimRow row);

    /**
     * 标记 Interaction 已成功回答。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @param answeredAt 回答完成时间。
     * @return 影响行数。
     */
    int markAnswered(@Param("tenantId") String tenantId,
                     @Param("userId") String userId,
                     @Param("interactionId") String interactionId,
                     @Param("answeredAt") Instant answeredAt);

    /**
     * 仅当 Interaction 仍由指定 continuation run 持有时标记回答已受理。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求标识。
     * @param continueRunId 当前持有 claim 的 continuation run 标识。
     * @param answeredAt 回答受理时间。
     * @return 影响行数。
     */
    int markAnsweredForRun(@Param("tenantId") String tenantId,
                           @Param("userId") String userId,
                           @Param("interactionId") String interactionId,
                           @Param("continueRunId") String continueRunId,
                           @Param("answeredAt") Instant answeredAt);

    /**
     * 把 RESPONDING 请求退回 WAITING 以便用户重试。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @return 影响行数。
     */
    int markWaiting(@Param("tenantId") String tenantId,
                    @Param("userId") String userId,
                    @Param("interactionId") String interactionId);

    /**
     * 仅允许持有当前 continueRunId 的续接 run 释放 Interaction claim。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @param continueRunId 当前持有响应 claim 的续接 run ID。
     * @return 影响行数。
     */
    int markWaitingForRun(@Param("tenantId") String tenantId,
                          @Param("userId") String userId,
                          @Param("interactionId") String interactionId,
                          @Param("continueRunId") String continueRunId);

    /**
     * 查询 continue run 已 FAILED/CANCELLED 的 RESPONDING Interaction。
     *
     * @param limit 最大候选数量。
     * @return 孤儿 claim 候选行。
     */
    List<ChatInteractionRequestRow> findRespondingWithTerminalContinuation(@Param("limit") int limit);

    /**
     * 查询终态、缺失 run 或缺失 execution 的 RESPONDING continuation。
     *
     * @param orphanBefore 孤儿启动宽限期截止时间。
     * @param limit 单轮最大候选数量。
     * @return 对账候选行。
     */
    List<ChatInteractionRequestRow> findRespondingReconcileCandidates(
            @Param("orphanBefore") Instant orphanBefore,
            @Param("limit") int limit);

    /**
     * 原子复核并释放终态或缺失 run 的 continuation claim。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 标识。
     * @param continueRunId 当前 claim 持有的 continuation run 标识。
     * @param orphanBefore 孤儿启动宽限期截止时间。
     * @return 影响行数。
     */
    int markWaitingIfContinuationOrphaned(@Param("tenantId") String tenantId,
                                           @Param("userId") String userId,
                                           @Param("interactionId") String interactionId,
                                           @Param("continueRunId") String continueRunId,
                                           @Param("orphanBefore") Instant orphanBefore);

    /**
     * 取消会话下仍开放的 Interaction 请求。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param cancelledAt 取消时间。
     * @return 影响行数。
     */
    int cancelOpenBySession(@Param("tenantId") String tenantId,
                            @Param("userId") String userId,
                            @Param("sessionId") String sessionId,
                            @Param("cancelledAt") Instant cancelledAt);

    /**
     * 标记等待请求已过期。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param interactionId Interaction 请求 ID。
     * @return 影响行数。
     */
    int markExpired(@Param("tenantId") String tenantId,
                    @Param("userId") String userId,
                    @Param("interactionId") String interactionId);
}
