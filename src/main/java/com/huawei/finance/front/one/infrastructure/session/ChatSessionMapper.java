package com.huawei.finance.front.one.infrastructure.session;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * fin_ex_chat_session_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatSessionMapper {
    @Insert("""
            INSERT INTO fin_ex_chat_session_t(
                id, tenant_id, user_id, title, status, channel, current_leaf_message_id,
                root_session_id, branch_source_session_id, branch_source_message_id,
                last_node_order, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{title}, #{status}, #{channel}, #{currentLeafMessageId},
                #{rootSessionId}, #{branchSourceSessionId}, #{branchSourceMessageId},
                #{lastNodeOrder}, #{metadataJson}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                status = EXCLUDED.status,
                channel = EXCLUDED.channel,
                current_leaf_message_id = EXCLUDED.current_leaf_message_id,
                root_session_id = EXCLUDED.root_session_id,
                branch_source_session_id = EXCLUDED.branch_source_session_id,
                branch_source_message_id = EXCLUDED.branch_source_message_id,
                last_node_order = EXCLUDED.last_node_order,
                metadata_json = EXCLUDED.metadata_json,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(@Param("id") String id,
                @Param("tenantId") String tenantId,
                @Param("userId") String userId,
                @Param("title") String title,
                @Param("status") String status,
                @Param("channel") String channel,
                @Param("currentLeafMessageId") String currentLeafMessageId,
                @Param("rootSessionId") String rootSessionId,
                @Param("branchSourceSessionId") String branchSourceSessionId,
                @Param("branchSourceMessageId") String branchSourceMessageId,
                @Param("lastNodeOrder") Long lastNodeOrder,
                @Param("metadataJson") String metadataJson,
                @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt);

    @Select("""
            SELECT id, tenant_id, user_id, title, status, channel, current_leaf_message_id,
                   root_session_id, branch_source_session_id, branch_source_message_id,
                   last_node_order, metadata_json, created_at, updated_at
            FROM fin_ex_chat_session_t
            WHERE id = #{sessionId}
            """)
    @Results(id = "chatSessionResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "current_leaf_message_id", property = "currentLeafMessageId"),
            @Result(column = "root_session_id", property = "rootSessionId"),
            @Result(column = "branch_source_session_id", property = "branchSourceSessionId"),
            @Result(column = "branch_source_message_id", property = "branchSourceMessageId"),
            @Result(column = "last_node_order", property = "lastNodeOrder"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatSessionRow findById(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, tenant_id, user_id, title, status, channel, current_leaf_message_id,
                   root_session_id, branch_source_session_id, branch_source_message_id,
                   last_node_order, metadata_json, created_at, updated_at
            FROM fin_ex_chat_session_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND id = #{sessionId}
            """)
    @Results(id = "chatSessionOwnedResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "current_leaf_message_id", property = "currentLeafMessageId"),
            @Result(column = "root_session_id", property = "rootSessionId"),
            @Result(column = "branch_source_session_id", property = "branchSourceSessionId"),
            @Result(column = "branch_source_message_id", property = "branchSourceMessageId"),
            @Result(column = "last_node_order", property = "lastNodeOrder"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatSessionRow findByOwnerAndId(@Param("tenantId") String tenantId,
                                    @Param("userId") String userId,
                                    @Param("sessionId") String sessionId);

    @Select("""
            SELECT id, tenant_id, user_id, title, status, channel, current_leaf_message_id,
                   root_session_id, branch_source_session_id, branch_source_message_id,
                   last_node_order, metadata_json, created_at, updated_at
            FROM fin_ex_chat_session_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
            ORDER BY updated_at DESC
            """)
    @Results(id = "chatSessionListResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "current_leaf_message_id", property = "currentLeafMessageId"),
            @Result(column = "root_session_id", property = "rootSessionId"),
            @Result(column = "branch_source_session_id", property = "branchSourceSessionId"),
            @Result(column = "branch_source_message_id", property = "branchSourceMessageId"),
            @Result(column = "last_node_order", property = "lastNodeOrder"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<ChatSessionRow> findByOwner(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("""
            <script>
            SELECT id, tenant_id, user_id, title, status, channel, current_leaf_message_id,
                   root_session_id, branch_source_session_id, branch_source_message_id,
                   last_node_order, metadata_json, created_at, updated_at
            FROM fin_ex_chat_session_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND status &lt;&gt; 'DELETED'
              <if test="cursorUpdatedAt != null">
              AND (
                    updated_at &lt; #{cursorUpdatedAt}
                    OR (updated_at = #{cursorUpdatedAt} AND id &lt; #{cursorId})
                  )
              </if>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    @Results(id = "chatSessionPageResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "current_leaf_message_id", property = "currentLeafMessageId"),
            @Result(column = "root_session_id", property = "rootSessionId"),
            @Result(column = "branch_source_session_id", property = "branchSourceSessionId"),
            @Result(column = "branch_source_message_id", property = "branchSourceMessageId"),
            @Result(column = "last_node_order", property = "lastNodeOrder"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<ChatSessionRow> findPageByOwner(@Param("tenantId") String tenantId,
                                         @Param("userId") String userId,
                                         @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                         @Param("cursorId") String cursorId,
                                         @Param("limit") int limit);

    @Select("""
            UPDATE fin_ex_chat_session_t
            SET last_node_order = last_node_order + 1,
                updated_at = #{updatedAt}
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND id = #{sessionId}
            RETURNING last_node_order
            """)
    Long incrementNodeOrder(@Param("tenantId") String tenantId,
                            @Param("userId") String userId,
                            @Param("sessionId") String sessionId,
                            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE fin_ex_chat_session_t
            SET current_leaf_message_id = #{leafMessageId},
                updated_at = #{updatedAt}
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND id = #{sessionId}
            """)
    int updateCurrentLeaf(@Param("tenantId") String tenantId,
                          @Param("userId") String userId,
                          @Param("sessionId") String sessionId,
                          @Param("leafMessageId") String leafMessageId,
                          @Param("updatedAt") Instant updatedAt);
}
