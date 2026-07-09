package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;
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
