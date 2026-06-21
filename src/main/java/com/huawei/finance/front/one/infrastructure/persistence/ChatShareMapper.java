package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * fin_ex_chat_share_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatShareMapper {
    @Insert("""
            INSERT INTO fin_ex_chat_share_t(
                id, tenant_id, owner_user_id, source_session_id, source_user_message_id,
                source_assistant_message_id, source_run_id, title, scope, visibility,
                status, expires_at, revoked_at, snapshot_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{ownerUserId}, #{sourceSessionId}, #{sourceUserMessageId},
                #{sourceAssistantMessageId}, #{sourceRunId}, #{title}, #{scope}, #{visibility},
                #{status}, #{expiresAt}, #{revokedAt}, #{snapshotJson}, #{createdAt}, #{updatedAt}
            )
            """)
    int insert(ChatShareRow row);

    @Update("""
            UPDATE fin_ex_chat_share_t
            SET title = #{title},
                visibility = #{visibility},
                status = #{status},
                expires_at = #{expiresAt},
                revoked_at = #{revokedAt},
                snapshot_json = #{snapshotJson},
                updated_at = #{updatedAt}
            WHERE id = #{id}
              AND tenant_id = #{tenantId}
              AND owner_user_id = #{ownerUserId}
            """)
    int update(ChatShareRow row);

    @Select("""
            SELECT id, tenant_id, owner_user_id, source_session_id, source_user_message_id,
                   source_assistant_message_id, source_run_id, title, scope, visibility,
                   status, expires_at, revoked_at, snapshot_json, created_at, updated_at
            FROM fin_ex_chat_share_t
            WHERE id = #{shareId}
            """)
    @Results(id = "chatShareResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "owner_user_id", property = "ownerUserId"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "source_user_message_id", property = "sourceUserMessageId"),
            @Result(column = "source_assistant_message_id", property = "sourceAssistantMessageId"),
            @Result(column = "source_run_id", property = "sourceRunId"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "revoked_at", property = "revokedAt"),
            @Result(column = "snapshot_json", property = "snapshotJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatShareRow findById(@Param("shareId") String shareId);

    @Select("""
            SELECT COUNT(1)
            FROM fin_ex_chat_share_t
            WHERE tenant_id = #{tenantId}
              AND owner_user_id = #{ownerUserId}
            """)
    long countByOwner(@Param("tenantId") String tenantId,
                      @Param("ownerUserId") String ownerUserId);

    @Select("""
            SELECT id, tenant_id, owner_user_id, source_session_id, source_user_message_id,
                   source_assistant_message_id, source_run_id, title, scope, visibility,
                   status, expires_at, revoked_at, snapshot_json, created_at, updated_at
            FROM fin_ex_chat_share_t
            WHERE tenant_id = #{tenantId}
              AND owner_user_id = #{ownerUserId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            OFFSET #{offset}
            """)
    @ResultMap("chatShareResultMap")
    List<ChatShareRow> findPageByOwner(@Param("tenantId") String tenantId,
                                       @Param("ownerUserId") String ownerUserId,
                                       @Param("limit") int limit,
                                       @Param("offset") long offset);

    @Update("""
            UPDATE fin_ex_chat_share_t
            SET status = 'REVOKED',
                revoked_at = #{revokedAt},
                updated_at = #{updatedAt}
            WHERE tenant_id = #{tenantId}
              AND owner_user_id = #{ownerUserId}
              AND source_session_id = #{sessionId}
              AND status = 'ACTIVE'
            """)
    int revokeActiveBySession(@Param("tenantId") String tenantId,
                              @Param("ownerUserId") String ownerUserId,
                              @Param("sessionId") String sessionId,
                              @Param("revokedAt") Instant revokedAt,
                              @Param("updatedAt") Instant updatedAt);
}
