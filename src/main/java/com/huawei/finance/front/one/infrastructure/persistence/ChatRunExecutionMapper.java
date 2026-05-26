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
 * fin_ex_chat_run_execution_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatRunExecutionMapper {
    @Insert("""
            INSERT INTO fin_ex_chat_run_execution_t(
                id, run_id, tenant_id, user_id, session_id, execution_status, owner_instance_id,
                heartbeat_at, lease_until, fencing_token, recovery_strategy, recovered_by_instance_id,
                recovery_attempts, recovery_lease_until, runtime_resume_token, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{runId}, #{tenantId}, #{userId}, #{sessionId}, #{executionStatus}, #{ownerInstanceId},
                #{heartbeatAt}, #{leaseUntil}, #{fencingToken}, #{recoveryStrategy}, #{recoveredByInstanceId},
                #{recoveryAttempts}, #{recoveryLeaseUntil}, #{runtimeResumeToken}, #{metadataJson}, #{createdAt}, #{updatedAt}
            )
            """)
    int insert(
            @Param("id") String id,
            @Param("runId") String runId,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("executionStatus") String executionStatus,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("heartbeatAt") Instant heartbeatAt,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("fencingToken") long fencingToken,
            @Param("recoveryStrategy") String recoveryStrategy,
            @Param("recoveredByInstanceId") String recoveredByInstanceId,
            @Param("recoveryAttempts") int recoveryAttempts,
            @Param("recoveryLeaseUntil") Instant recoveryLeaseUntil,
            @Param("runtimeResumeToken") String runtimeResumeToken,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Select("""
            SELECT id, run_id, tenant_id, user_id, session_id, execution_status, owner_instance_id,
                   heartbeat_at, lease_until, fencing_token, recovery_strategy, recovered_by_instance_id,
                   recovery_attempts, recovery_lease_until, runtime_resume_token, metadata_json, created_at, updated_at
            FROM fin_ex_chat_run_execution_t
            WHERE run_id = #{runId}
            """)
    @Results(id = "chatRunExecutionResultMap", value = {
            @Result(column = "run_id", property = "runId"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "execution_status", property = "executionStatus"),
            @Result(column = "owner_instance_id", property = "ownerInstanceId"),
            @Result(column = "heartbeat_at", property = "heartbeatAt"),
            @Result(column = "lease_until", property = "leaseUntil"),
            @Result(column = "fencing_token", property = "fencingToken"),
            @Result(column = "recovery_strategy", property = "recoveryStrategy"),
            @Result(column = "recovered_by_instance_id", property = "recoveredByInstanceId"),
            @Result(column = "recovery_attempts", property = "recoveryAttempts"),
            @Result(column = "recovery_lease_until", property = "recoveryLeaseUntil"),
            @Result(column = "runtime_resume_token", property = "runtimeResumeToken"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatRunExecutionRow findByRunId(@Param("runId") String runId);

    @Update("""
            UPDATE fin_ex_chat_run_execution_t
            SET heartbeat_at = CURRENT_TIMESTAMP,
                lease_until = #{leaseUntil},
                updated_at = CURRENT_TIMESTAMP
            WHERE run_id = #{runId}
              AND owner_instance_id = #{ownerInstanceId}
              AND execution_status IN ('RUNNING', 'CANCELLING')
            """)
    int heartbeat(@Param("runId") String runId,
                  @Param("ownerInstanceId") String ownerInstanceId,
                  @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            UPDATE fin_ex_chat_run_execution_t
            SET execution_status = #{terminalStatus},
                updated_at = CURRENT_TIMESTAMP
            WHERE run_id = #{runId}
              AND execution_status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
            """)
    int markTerminal(@Param("runId") String runId,
                     @Param("terminalStatus") String terminalStatus);

    @Select("""
            SELECT id, run_id, tenant_id, user_id, session_id, execution_status, owner_instance_id,
                   heartbeat_at, lease_until, fencing_token, recovery_strategy, recovered_by_instance_id,
                   recovery_attempts, recovery_lease_until, runtime_resume_token, metadata_json, created_at, updated_at
            FROM fin_ex_chat_run_execution_t
            WHERE execution_status IN ('RUNNING', 'CANCELLING')
              AND lease_until < CURRENT_TIMESTAMP
            ORDER BY lease_until ASC
            LIMIT #{limit}
            """)
    @ResultMap("chatRunExecutionResultMap")
    List<ChatRunExecutionRow> findLeaseExpired(@Param("limit") int limit);

    @Select("""
            SELECT id, run_id, tenant_id, user_id, session_id, execution_status, owner_instance_id,
                   heartbeat_at, lease_until, fencing_token, recovery_strategy, recovered_by_instance_id,
                   recovery_attempts, recovery_lease_until, runtime_resume_token, metadata_json, created_at, updated_at
            FROM fin_ex_chat_run_execution_t
            WHERE execution_status = 'RECOVERING'
              AND recovery_lease_until < CURRENT_TIMESTAMP
            ORDER BY recovery_lease_until ASC
            LIMIT #{limit}
            """)
    @ResultMap("chatRunExecutionResultMap")
    List<ChatRunExecutionRow> findRecoveryExpired(@Param("limit") int limit);

    @Update("""
            UPDATE fin_ex_chat_run_execution_t
            SET execution_status = 'RECOVERING',
                recovered_by_instance_id = #{recoveredByInstanceId},
                recovery_strategy = #{strategy},
                recovery_attempts = recovery_attempts + 1,
                recovery_lease_until = #{recoveryLeaseUntil},
                fencing_token = fencing_token + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE run_id = #{runId}
              AND (
                    (execution_status IN ('RUNNING', 'CANCELLING') AND lease_until < CURRENT_TIMESTAMP)
                    OR (execution_status = 'RECOVERING' AND recovery_lease_until < CURRENT_TIMESTAMP)
              )
            """)
    int tryClaimRecovering(@Param("runId") String runId,
                           @Param("recoveredByInstanceId") String recoveredByInstanceId,
                           @Param("strategy") String strategy,
                           @Param("recoveryLeaseUntil") Instant recoveryLeaseUntil);

    @Update("""
            UPDATE fin_ex_chat_run_execution_t
            SET execution_status = 'RUNNING',
                owner_instance_id = #{ownerInstanceId},
                heartbeat_at = CURRENT_TIMESTAMP,
                lease_until = #{leaseUntil},
                recovery_lease_until = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE run_id = #{runId}
              AND execution_status = 'RECOVERING'
              AND recovered_by_instance_id = #{ownerInstanceId}
            """)
    int markTakeoverRunning(@Param("runId") String runId,
                            @Param("ownerInstanceId") String ownerInstanceId,
                            @Param("leaseUntil") Instant leaseUntil);

    @Select("""
            SELECT COUNT(1)
            FROM fin_ex_chat_run_execution_t
            WHERE run_id = #{runId}
              AND execution_status IN ('RUNNING', 'CANCELLING')
              AND lease_until < CURRENT_TIMESTAMP
            """)
    int countLeaseExpired(@Param("runId") String runId);
}
