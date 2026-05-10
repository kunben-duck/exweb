package com.huawei.finance.front.one.infrastructure.runtime.binding.mybatis;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * fin_ex_runtime_binding_t 的 MyBatis mapper。
 */
@Mapper
public interface RuntimeBindingMapper {
    @Insert("""
            INSERT INTO fin_ex_runtime_binding_t(
                id, tenant_id, user_id, chat_session_id, provider, runtime_session_id,
                status, last_run_id, expires_at, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{chatSessionId}, #{provider}, #{runtimeSessionId},
                #{status}, #{lastRunId}, #{expiresAt}, #{metadataJson}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                runtime_session_id = EXCLUDED.runtime_session_id,
                status = EXCLUDED.status,
                last_run_id = EXCLUDED.last_run_id,
                expires_at = EXCLUDED.expires_at,
                metadata_json = EXCLUDED.metadata_json,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("chatSessionId") String chatSessionId,
            @Param("provider") String provider,
            @Param("runtimeSessionId") String runtimeSessionId,
            @Param("status") String status,
            @Param("lastRunId") String lastRunId,
            @Param("expiresAt") Instant expiresAt,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Select("""
            SELECT id, tenant_id, user_id, chat_session_id, provider, runtime_session_id,
                   status, last_run_id, expires_at, metadata_json, created_at, updated_at
            FROM fin_ex_runtime_binding_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND chat_session_id = #{sessionId}
              AND status = 'ACTIVE'
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    @Results(id = "runtimeBindingResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "chat_session_id", property = "chatSessionId"),
            @Result(column = "runtime_session_id", property = "runtimeSessionId"),
            @Result(column = "last_run_id", property = "lastRunId"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    RuntimeBindingRow findActive(@Param("tenantId") String tenantId,
                                 @Param("userId") String userId,
                                 @Param("sessionId") String sessionId);
}
