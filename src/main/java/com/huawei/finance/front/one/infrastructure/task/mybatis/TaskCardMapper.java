package com.huawei.finance.front.one.infrastructure.task.mybatis;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * fin_ex_task_card_t 的 MyBatis mapper。
 */
@Mapper
public interface TaskCardMapper {
    @Insert("""
            INSERT INTO fin_ex_task_card_t(
                task_id, tenant_id, user_id, chat_session_id, binding_id, task_goal, task_domain,
                agent_code, agent_session_id, task_status, raw_normalized_status, required_inputs_json,
                collected_slots_json, last_agent_message, confirmation_question, expires_at,
                created_at, updated_at, metadata_json
            )
            VALUES (
                #{taskId}, #{tenantId}, #{userId}, #{chatSessionId}, #{bindingId}, #{taskGoal}, #{taskDomain},
                #{agentCode}, #{agentSessionId}, #{taskStatus}, #{rawNormalizedStatus}, #{requiredInputsJson},
                #{collectedSlotsJson}, #{lastAgentMessage}, #{confirmationQuestion}, #{expiresAt},
                #{createdAt}, #{updatedAt}, #{metadataJson}
            )
            ON CONFLICT (task_id) DO UPDATE SET
                binding_id = EXCLUDED.binding_id,
                task_goal = EXCLUDED.task_goal,
                task_domain = EXCLUDED.task_domain,
                agent_code = EXCLUDED.agent_code,
                agent_session_id = EXCLUDED.agent_session_id,
                task_status = EXCLUDED.task_status,
                raw_normalized_status = EXCLUDED.raw_normalized_status,
                required_inputs_json = EXCLUDED.required_inputs_json,
                collected_slots_json = EXCLUDED.collected_slots_json,
                last_agent_message = EXCLUDED.last_agent_message,
                confirmation_question = EXCLUDED.confirmation_question,
                expires_at = EXCLUDED.expires_at,
                updated_at = EXCLUDED.updated_at,
                metadata_json = EXCLUDED.metadata_json
            """)
    void upsert(@Param("taskId") String taskId,
                @Param("tenantId") String tenantId,
                @Param("userId") String userId,
                @Param("chatSessionId") String chatSessionId,
                @Param("bindingId") String bindingId,
                @Param("taskGoal") String taskGoal,
                @Param("taskDomain") String taskDomain,
                @Param("agentCode") String agentCode,
                @Param("agentSessionId") String agentSessionId,
                @Param("taskStatus") String taskStatus,
                @Param("rawNormalizedStatus") String rawNormalizedStatus,
                @Param("requiredInputsJson") String requiredInputsJson,
                @Param("collectedSlotsJson") String collectedSlotsJson,
                @Param("lastAgentMessage") String lastAgentMessage,
                @Param("confirmationQuestion") String confirmationQuestion,
                @Param("expiresAt") Instant expiresAt,
                @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt,
                @Param("metadataJson") String metadataJson);

    @Select("""
            SELECT task_id, tenant_id, user_id, chat_session_id, binding_id, task_goal, task_domain,
                   agent_code, agent_session_id, task_status, raw_normalized_status, required_inputs_json,
                   collected_slots_json, last_agent_message, confirmation_question, expires_at,
                   created_at, updated_at, metadata_json
            FROM fin_ex_task_card_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND chat_session_id = #{sessionId}
              AND task_status IN ('ACTIVE', 'REQUIRES_USER_INPUT', 'WAITING_EXTERNAL_SYSTEM', 'WAITING_USER_CONFIRMATION')
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    @Results(id = "taskCardResultMap", value = {
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "chat_session_id", property = "chatSessionId"),
            @Result(column = "binding_id", property = "bindingId"),
            @Result(column = "task_goal", property = "taskGoal"),
            @Result(column = "task_domain", property = "taskDomain"),
            @Result(column = "agent_code", property = "agentCode"),
            @Result(column = "agent_session_id", property = "agentSessionId"),
            @Result(column = "task_status", property = "taskStatus"),
            @Result(column = "raw_normalized_status", property = "rawNormalizedStatus"),
            @Result(column = "required_inputs_json", property = "requiredInputsJson"),
            @Result(column = "collected_slots_json", property = "collectedSlotsJson"),
            @Result(column = "last_agent_message", property = "lastAgentMessage"),
            @Result(column = "confirmation_question", property = "confirmationQuestion"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "metadata_json", property = "metadataJson")
    })
    TaskCardRow findActive(@Param("tenantId") String tenantId,
                           @Param("userId") String userId,
                           @Param("sessionId") String sessionId);

    @Select("""
            SELECT task_id, tenant_id, user_id, chat_session_id, binding_id, task_goal, task_domain,
                   agent_code, agent_session_id, task_status, raw_normalized_status, required_inputs_json,
                   collected_slots_json, last_agent_message, confirmation_question, expires_at,
                   created_at, updated_at, metadata_json
            FROM fin_ex_task_card_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND chat_session_id = #{sessionId}
              AND task_id = #{taskId}
            LIMIT 1
            """)
    @Results(id = "taskCardByIdResultMap", value = {
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "chat_session_id", property = "chatSessionId"),
            @Result(column = "binding_id", property = "bindingId"),
            @Result(column = "task_goal", property = "taskGoal"),
            @Result(column = "task_domain", property = "taskDomain"),
            @Result(column = "agent_code", property = "agentCode"),
            @Result(column = "agent_session_id", property = "agentSessionId"),
            @Result(column = "task_status", property = "taskStatus"),
            @Result(column = "raw_normalized_status", property = "rawNormalizedStatus"),
            @Result(column = "required_inputs_json", property = "requiredInputsJson"),
            @Result(column = "collected_slots_json", property = "collectedSlotsJson"),
            @Result(column = "last_agent_message", property = "lastAgentMessage"),
            @Result(column = "confirmation_question", property = "confirmationQuestion"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "metadata_json", property = "metadataJson")
    })
    TaskCardRow findByTaskId(@Param("tenantId") String tenantId,
                             @Param("userId") String userId,
                             @Param("sessionId") String sessionId,
                             @Param("taskId") String taskId);
}
