package com.huawei.it.ex.one.chat.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_message_feedback_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatFeedbackMapper {
    /**
     * 创建当前用户对 assistant 消息的反馈。
     *
     * @param row 反馈写入行，包含归属、messageId、runId、rating、status 和审计时间。
     * @return 影响行数。
     */
    int insert(ChatMessageFeedbackRow row);

    /**
     * 更新当前用户对同一消息的反馈。
     *
     * @param row 反馈更新行，id、tenantId、userId、messageId 用于定位记录。
     * @return 影响行数。
     */
    int update(ChatMessageFeedbackRow row);

    /**
     * 取消当前用户对指定消息的反馈。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 目标 assistant 消息标识。
     * @param updatedAt 取消时间。
     * @return 影响行数。
     */
    int cancelCurrent(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 查询当前用户对指定消息的反馈记录。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 目标消息标识。
     * @return 反馈记录。
     */
    Optional<ChatMessageFeedbackRow> findByMessage(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId
    );

    /**
     * 批量查询当前用户对一组消息的 active 反馈。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageIds 待装配反馈的消息 ID 列表。
     * @return active 反馈记录列表。
     */
    List<ChatMessageFeedbackRow> findActiveByMessages(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageIds") List<String> messageIds
    );
}
