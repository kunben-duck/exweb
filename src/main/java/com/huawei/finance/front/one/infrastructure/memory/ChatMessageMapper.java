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

/**
 * fin_ex_chat_message_t 表的 MyBatis Mapper。
 *
 * <p>短期记忆的数据库读写集中在这里，组合缓存策略仍由外层仓储负责。</p>
 */
@Mapper
public interface ChatMessageMapper {
    @Insert("""
            INSERT INTO fin_ex_chat_message_t(id, tenant_id, user_id, session_id, role, content, token_count, created_at)
            VALUES (#{id}, #{tenantId}, #{userId}, #{sessionId}, #{role}, #{content}, #{tokenCount}, #{createdAt})
            """)
    void insert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("role") String role,
            @Param("content") String content,
            @Param("tokenCount") Integer tokenCount,
            @Param("createdAt") Instant createdAt
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, role, content, token_count, created_at
            FROM fin_ex_chat_message_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    @Results(id = "chatMessageResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessageRow> findRecentByOwner(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, role, content, token_count, created_at
            FROM fin_ex_chat_message_t
            WHERE session_id = #{sessionId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    @Results(id = "chatMessageWithoutOwnerResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessageRow> findRecentBySession(@Param("sessionId") String sessionId, @Param("limit") int limit);

    @Select("""
            SELECT id, tenant_id, user_id, session_id, role, content, token_count, created_at
            FROM fin_ex_chat_message_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND (
                    #{cursorCreatedAt} IS NULL
                    OR created_at < #{cursorCreatedAt}
                    OR (created_at = #{cursorCreatedAt} AND id < #{cursorId})
                  )
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    @Results(id = "chatMessagePageResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessageRow> findPageByOwner(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, role, content, token_count, created_at
            FROM fin_ex_chat_message_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND id = #{messageId}
            """)
    @Results(id = "chatMessageByIdResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "created_at", property = "createdAt")
    })
    Optional<ChatMessageRow> findByOwnerAndId(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId
    );
}
