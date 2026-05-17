package com.huawei.finance.front.one.infrastructure.session;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * fin_ex_chat_session_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatSessionMapper {
    @Insert("""
            INSERT INTO fin_ex_chat_session_t(id, tenant_id, user_id, title, status, channel, created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{userId}, #{title}, #{status}, #{channel}, #{createdAt}, #{updatedAt})
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                status = EXCLUDED.status,
                channel = EXCLUDED.channel,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(@Param("id") String id,
                @Param("tenantId") String tenantId,
                @Param("userId") String userId,
                @Param("title") String title,
                @Param("status") String status,
                @Param("channel") String channel,
                @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt);

    @Select("""
            SELECT id, tenant_id, user_id, title, status, channel, created_at, updated_at
            FROM fin_ex_chat_session_t
            WHERE id = #{sessionId}
            """)
    @Results(id = "chatSessionResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatSessionRow findById(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, tenant_id, user_id, title, status, channel, created_at, updated_at
            FROM fin_ex_chat_session_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND id = #{sessionId}
            """)
    @Results(id = "chatSessionOwnedResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatSessionRow findByOwnerAndId(@Param("tenantId") String tenantId,
                                    @Param("userId") String userId,
                                    @Param("sessionId") String sessionId);

    @Select("""
            SELECT id, tenant_id, user_id, title, status, channel, created_at, updated_at
            FROM fin_ex_chat_session_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
            ORDER BY updated_at DESC
            """)
    @Results(id = "chatSessionListResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<ChatSessionRow> findByOwner(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("""
            SELECT id, tenant_id, user_id, title, status, channel, created_at, updated_at
            FROM fin_ex_chat_session_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND status <> 'DELETED'
              AND (
                    #{cursorUpdatedAt} IS NULL
                    OR updated_at < #{cursorUpdatedAt}
                    OR (updated_at = #{cursorUpdatedAt} AND id < #{cursorId})
                  )
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    @Results(id = "chatSessionPageResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<ChatSessionRow> findPageByOwner(@Param("tenantId") String tenantId,
                                         @Param("userId") String userId,
                                         @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                         @Param("cursorId") String cursorId,
                                         @Param("limit") int limit);
}
