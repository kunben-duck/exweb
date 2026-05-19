package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * fin_ex_chat_run_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatRunMapper {
    @Insert("""
            INSERT INTO fin_ex_chat_run_t(
                id, tenant_id, user_id, session_id, status, route_type, agent_code, runtime_provider,
                runtime_session_id, run_mode, parent_message_id, user_message_id, assistant_message_id,
                first_seq, last_seq, cancel_reason, started_at, finished_at,
                metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{status}, #{routeType}, #{agentCode}, #{runtimeProvider},
                #{runtimeSessionId}, #{runMode}, #{parentMessageId}, #{userMessageId}, #{assistantMessageId},
                #{firstSeq}, #{lastSeq}, #{cancelReason}, #{startedAt}, #{finishedAt},
                #{metadataJson}, #{createdAt}, #{updatedAt}
            )
            """)
    int insert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("status") String status,
            @Param("routeType") String routeType,
            @Param("agentCode") String agentCode,
            @Param("runtimeProvider") String runtimeProvider,
            @Param("runtimeSessionId") String runtimeSessionId,
            @Param("runMode") String runMode,
            @Param("parentMessageId") String parentMessageId,
            @Param("userMessageId") String userMessageId,
            @Param("assistantMessageId") String assistantMessageId,
            @Param("firstSeq") Long firstSeq,
            @Param("lastSeq") Long lastSeq,
            @Param("cancelReason") String cancelReason,
            @Param("startedAt") Instant startedAt,
            @Param("finishedAt") Instant finishedAt,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Update("""
            UPDATE fin_ex_chat_run_t
            SET status = CASE
                    WHEN status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN status
                    ELSE #{status}
                END,
                route_type = COALESCE(route_type, #{routeType}),
                agent_code = COALESCE(agent_code, #{agentCode}),
                runtime_provider = COALESCE(runtime_provider, #{runtimeProvider}),
                runtime_session_id = COALESCE(#{runtimeSessionId}, runtime_session_id),
                run_mode = COALESCE(run_mode, #{runMode}),
                parent_message_id = COALESCE(parent_message_id, #{parentMessageId}),
                user_message_id = COALESCE(user_message_id, #{userMessageId}),
                assistant_message_id = COALESCE(#{assistantMessageId}, assistant_message_id),
                first_seq = COALESCE(first_seq, #{firstSeq}),
                last_seq = CASE
                    WHEN status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN last_seq
                    ELSE #{lastSeq}
                END,
                cancel_reason = COALESCE(#{cancelReason}, cancel_reason),
                started_at = COALESCE(started_at, #{startedAt}),
                finished_at = CASE
                    WHEN status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN finished_at
                    ELSE #{finishedAt}
                END,
                metadata_json = COALESCE(#{metadataJson}, metadata_json),
                updated_at = CASE
                    WHEN status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN updated_at
                    ELSE #{updatedAt}
                END
            WHERE id = #{id}
            """)
    int updateExisting(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("status") String status,
            @Param("routeType") String routeType,
            @Param("agentCode") String agentCode,
            @Param("runtimeProvider") String runtimeProvider,
            @Param("runtimeSessionId") String runtimeSessionId,
            @Param("runMode") String runMode,
            @Param("parentMessageId") String parentMessageId,
            @Param("userMessageId") String userMessageId,
            @Param("assistantMessageId") String assistantMessageId,
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
                   runtime_session_id, run_mode, parent_message_id, user_message_id, assistant_message_id,
                   first_seq, last_seq, cancel_reason, started_at, finished_at,
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
            @Result(column = "run_mode", property = "runMode"),
            @Result(column = "parent_message_id", property = "parentMessageId"),
            @Result(column = "user_message_id", property = "userMessageId"),
            @Result(column = "assistant_message_id", property = "assistantMessageId"),
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
                   runtime_session_id, run_mode, parent_message_id, user_message_id, assistant_message_id,
                   first_seq, last_seq, cancel_reason, started_at, finished_at,
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
            @Result(column = "run_mode", property = "runMode"),
            @Result(column = "parent_message_id", property = "parentMessageId"),
            @Result(column = "user_message_id", property = "userMessageId"),
            @Result(column = "assistant_message_id", property = "assistantMessageId"),
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
                   runtime_session_id, run_mode, parent_message_id, user_message_id, assistant_message_id,
                   first_seq, last_seq, cancel_reason, started_at, finished_at,
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
            @Result(column = "run_mode", property = "runMode"),
            @Result(column = "parent_message_id", property = "parentMessageId"),
            @Result(column = "user_message_id", property = "userMessageId"),
            @Result(column = "assistant_message_id", property = "assistantMessageId"),
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
