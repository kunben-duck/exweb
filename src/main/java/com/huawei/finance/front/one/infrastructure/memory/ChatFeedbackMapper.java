package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
            ON CONFLICT (id) DO UPDATE SET
                run_id = EXCLUDED.run_id,
                rating = EXCLUDED.rating,
                reason_code = EXCLUDED.reason_code,
                comment_text = EXCLUDED.comment_text,
                metadata_json = EXCLUDED.metadata_json,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(
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
