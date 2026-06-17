package com.huawei.finance.front.one.infrastructure.runtime;

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
 * fin_ex_runtime_binding_t 的 MyBatis mapper。
 */
@Mapper
public interface RuntimeBindingMapper {
    @Insert("""
            INSERT INTO fin_ex_runtime_binding_t(
                id, tenant_id, user_id, chat_session_id, provider, leaf_message_id, runtime_session_id,
                status, last_run_id, expires_at, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{chatSessionId}, #{provider}, #{leafMessageId}, #{runtimeSessionId},
                #{status}, #{lastRunId}, #{expiresAt}, #{metadataJson}, #{createdAt}, #{updatedAt}
            )
            """)
    int insert(RuntimeBindingRow row);

    @Update("""
            UPDATE fin_ex_runtime_binding_t
            SET leaf_message_id = #{leafMessageId},
                runtime_session_id = #{runtimeSessionId},
                status = #{status},
                last_run_id = #{lastRunId},
                expires_at = #{expiresAt},
                metadata_json = #{metadataJson},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int update(RuntimeBindingRow row);

    @Select("""
            <script>
            SELECT id, tenant_id, user_id, chat_session_id, provider, runtime_session_id,
                   leaf_message_id, status, last_run_id, expires_at, metadata_json, created_at, updated_at
            FROM fin_ex_runtime_binding_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND chat_session_id = #{sessionId}
              AND provider = #{provider}
              <choose>
                <when test="leafMessageId == null">
              AND leaf_message_id IS NULL
                </when>
                <otherwise>
              AND leaf_message_id = #{leafMessageId}
                </otherwise>
              </choose>
              AND status = 'ACTIVE'
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            ORDER BY updated_at DESC
            LIMIT 1
            </script>
            """)
    @Results(id = "runtimeBindingResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "chat_session_id", property = "chatSessionId"),
            @Result(column = "leaf_message_id", property = "leafMessageId"),
            @Result(column = "runtime_session_id", property = "runtimeSessionId"),
            @Result(column = "last_run_id", property = "lastRunId"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    RuntimeBindingRow findActive(@Param("tenantId") String tenantId,
                                 @Param("userId") String userId,
                                 @Param("sessionId") String sessionId,
                                 @Param("provider") String provider,
                                 @Param("leafMessageId") String leafMessageId);

    @Select("""
            SELECT id, tenant_id, user_id, chat_session_id, provider, runtime_session_id,
                   leaf_message_id, status, last_run_id, expires_at, metadata_json, created_at, updated_at
            FROM fin_ex_runtime_binding_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND chat_session_id = #{sessionId}
              AND provider = #{provider}
              AND status = 'ACTIVE'
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            ORDER BY updated_at DESC
            """)
    @ResultMap("runtimeBindingResultMap")
    List<RuntimeBindingRow> findActiveBySession(@Param("tenantId") String tenantId,
                                                @Param("userId") String userId,
                                                @Param("sessionId") String sessionId,
                                                @Param("provider") String provider);
}
