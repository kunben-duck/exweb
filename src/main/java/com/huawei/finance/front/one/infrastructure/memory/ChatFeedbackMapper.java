package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * fin_ex_message_feedback_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatFeedbackMapper {
    @Insert("""
            INSERT INTO fin_ex_message_feedback_t(
                id, tenant_id, user_id, session_id, message_id, run_id,
                rating, status, reason_code, comment_text, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{messageId}, #{runId},
                #{rating}, #{status}, #{reasonCode}, #{commentText}, #{metadataJson}, #{createdAt}, #{updatedAt}
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
            @Param("status") String status,
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
                status = #{status},
                reason_code = #{reasonCode},
                comment_text = #{commentText},
                metadata_json = #{metadataJson},
                updated_at = #{updatedAt}
            WHERE id = #{id}
              AND tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND message_id = #{messageId}
            """)
    int update(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageId") String messageId,
            @Param("runId") String runId,
            @Param("rating") String rating,
            @Param("status") String status,
            @Param("reasonCode") String reasonCode,
            @Param("commentText") String commentText,
            @Param("metadataJson") String metadataJson,
            @Param("updatedAt") Instant updatedAt
    );

    @Update("""
            UPDATE fin_ex_message_feedback_t
            SET status = 'CANCELLED',
                updated_at = #{updatedAt}
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND message_id = #{messageId}
            """)
    int cancelCurrent(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId,
            @Param("updatedAt") Instant updatedAt
    );

    @Select("""
            SELECT *
            FROM fin_ex_message_feedback_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND message_id = #{messageId}
            """)
    @Results(id = "chatMessageFeedbackResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "message_id", property = "messageId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "reason_code", property = "reasonCode"),
            @Result(column = "comment_text", property = "commentText"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<ChatMessageFeedbackRow> findByMessage(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId
    );

    @Select("""
            <script>
            SELECT *
            FROM fin_ex_message_feedback_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND status = 'ACTIVE'
              <choose>
                <when test="messageIds != null and messageIds.size() > 0">
              AND message_id IN
                  <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                      #{messageId}
                  </foreach>
                </when>
                <otherwise>
              AND 1 = 0
                </otherwise>
              </choose>
            </script>
            """)
    @Results(id = "activeChatMessageFeedbackResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "message_id", property = "messageId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "reason_code", property = "reasonCode"),
            @Result(column = "comment_text", property = "commentText"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<ChatMessageFeedbackRow> findActiveByMessages(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageIds") List<String> messageIds
    );
}
