package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * fin_ex_chat_run_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatRunMapper {
    @Insert("""
            INSERT INTO fin_ex_chat_run_t(
                id, tenant_id, user_id, session_id, status, route_type, agent_code, runtime_provider,
                runtime_session_id, first_seq, last_seq, cancel_reason, started_at, finished_at,
                metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{status}, #{routeType}, #{agentCode}, #{runtimeProvider},
                #{runtimeSessionId}, #{firstSeq}, #{lastSeq}, #{cancelReason}, #{startedAt}, #{finishedAt},
                #{metadataJson}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                status = CASE
                    WHEN fin_ex_chat_run_t.status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN fin_ex_chat_run_t.status
                    ELSE EXCLUDED.status
                END,
                route_type = COALESCE(fin_ex_chat_run_t.route_type, EXCLUDED.route_type),
                agent_code = COALESCE(fin_ex_chat_run_t.agent_code, EXCLUDED.agent_code),
                runtime_provider = COALESCE(fin_ex_chat_run_t.runtime_provider, EXCLUDED.runtime_provider),
                runtime_session_id = COALESCE(EXCLUDED.runtime_session_id, fin_ex_chat_run_t.runtime_session_id),
                first_seq = COALESCE(fin_ex_chat_run_t.first_seq, EXCLUDED.first_seq),
                last_seq = CASE
                    WHEN fin_ex_chat_run_t.status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN fin_ex_chat_run_t.last_seq
                    ELSE EXCLUDED.last_seq
                END,
                cancel_reason = COALESCE(EXCLUDED.cancel_reason, fin_ex_chat_run_t.cancel_reason),
                started_at = COALESCE(fin_ex_chat_run_t.started_at, EXCLUDED.started_at),
                finished_at = CASE
                    WHEN fin_ex_chat_run_t.status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN fin_ex_chat_run_t.finished_at
                    ELSE EXCLUDED.finished_at
                END,
                metadata_json = COALESCE(EXCLUDED.metadata_json, fin_ex_chat_run_t.metadata_json),
                updated_at = CASE
                    WHEN fin_ex_chat_run_t.status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN fin_ex_chat_run_t.updated_at
                    ELSE EXCLUDED.updated_at
                END
            """)
    void upsert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("status") String status,
            @Param("routeType") String routeType,
            @Param("agentCode") String agentCode,
            @Param("runtimeProvider") String runtimeProvider,
            @Param("runtimeSessionId") String runtimeSessionId,
            @Param("firstSeq") Long firstSeq,
            @Param("lastSeq") Long lastSeq,
            @Param("cancelReason") String cancelReason,
            @Param("startedAt") Instant startedAt,
            @Param("finishedAt") Instant finishedAt,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, status, route_type, agent_code, runtime_provider,
                   runtime_session_id, first_seq, last_seq, cancel_reason, started_at, finished_at,
                   metadata_json, created_at, updated_at
            FROM fin_ex_chat_run_t
            WHERE id = #{runId}
            """)
    @Results(id = "chatRunResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "route_type", property = "routeType"),
            @Result(column = "agent_code", property = "agentCode"),
            @Result(column = "runtime_provider", property = "runtimeProvider"),
            @Result(column = "runtime_session_id", property = "runtimeSessionId"),
            @Result(column = "first_seq", property = "firstSeq"),
            @Result(column = "last_seq", property = "lastSeq"),
            @Result(column = "cancel_reason", property = "cancelReason"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "finished_at", property = "finishedAt"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatRunRow findById(@Param("runId") String runId);

    @Select("""
            SELECT id, tenant_id, user_id, session_id, status, route_type, agent_code, runtime_provider,
                   runtime_session_id, first_seq, last_seq, cancel_reason, started_at, finished_at,
                   metadata_json, created_at, updated_at
            FROM fin_ex_chat_run_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND id = #{runId}
            """)
    @Results(id = "chatRunOwnerResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "route_type", property = "routeType"),
            @Result(column = "agent_code", property = "agentCode"),
            @Result(column = "runtime_provider", property = "runtimeProvider"),
            @Result(column = "runtime_session_id", property = "runtimeSessionId"),
            @Result(column = "first_seq", property = "firstSeq"),
            @Result(column = "last_seq", property = "lastSeq"),
            @Result(column = "cancel_reason", property = "cancelReason"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "finished_at", property = "finishedAt"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatRunRow findByOwnerAndId(@Param("tenantId") String tenantId,
                                @Param("userId") String userId,
                                @Param("runId") String runId);

    @Select("""
            SELECT id, tenant_id, user_id, session_id, status, route_type, agent_code, runtime_provider,
                   runtime_session_id, first_seq, last_seq, cancel_reason, started_at, finished_at,
                   metadata_json, created_at, updated_at
            FROM fin_ex_chat_run_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND status IN ('RUNNING', 'CANCELLING')
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    @Results(id = "chatRunActiveResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "route_type", property = "routeType"),
            @Result(column = "agent_code", property = "agentCode"),
            @Result(column = "runtime_provider", property = "runtimeProvider"),
            @Result(column = "runtime_session_id", property = "runtimeSessionId"),
            @Result(column = "first_seq", property = "firstSeq"),
            @Result(column = "last_seq", property = "lastSeq"),
            @Result(column = "cancel_reason", property = "cancelReason"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "finished_at", property = "finishedAt"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatRunRow findActiveBySession(@Param("tenantId") String tenantId,
                                   @Param("userId") String userId,
                                   @Param("sessionId") String sessionId);
}
