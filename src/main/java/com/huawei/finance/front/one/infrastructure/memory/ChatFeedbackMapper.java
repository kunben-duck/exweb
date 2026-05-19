package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * fin_ex_message_feedback_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatFeedbackMapper {
    @Insert("""
            INSERT INTO fin_ex_message_feedback_t(
                id, tenant_id, user_id, session_id, message_id, run_id,
                rating, reason_code, comment_text, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{messageId}, #{runId},
                #{rating}, #{reasonCode}, #{commentText}, #{metadataJson}, #{createdAt}, #{updatedAt}
            )
            """)
    int insert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageId") String messageId,
            @Param("runId") String runId,
            @Param("rating") String rating,
            @Param("reasonCode") String reasonCode,
            @Param("commentText") String commentText,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Update("""
            UPDATE fin_ex_message_feedback_t
            SET run_id = #{runId},
                rating = #{rating},
                reason_code = #{reasonCode},
                comment_text = #{commentText},
                metadata_json = #{metadataJson},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int update(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageId") String messageId,
            @Param("runId") String runId,
            @Param("rating") String rating,
            @Param("reasonCode") String reasonCode,
            @Param("commentText") String commentText,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );
}
