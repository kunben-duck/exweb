package com.huawei.finance.front.one.infrastructure.task.mybatis;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_task_event_t 的 MyBatis mapper。
 */
@Mapper
public interface TaskEventMapper {
    @Insert("""
            INSERT INTO fin_ex_task_event_t(
                id, tenant_id, user_id, chat_session_id, task_id, run_id, event_type,
                from_status, to_status, payload_json, created_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{chatSessionId}, #{taskId}, #{runId}, #{eventType},
                #{fromStatus}, #{toStatus}, #{payloadJson}, #{createdAt}
            )
            """)
    void insert(@Param("id") String id,
                @Param("tenantId") String tenantId,
                @Param("userId") String userId,
                @Param("chatSessionId") String chatSessionId,
                @Param("taskId") String taskId,
                @Param("runId") String runId,
                @Param("eventType") String eventType,
                @Param("fromStatus") String fromStatus,
                @Param("toStatus") String toStatus,
                @Param("payloadJson") String payloadJson,
                @Param("createdAt") Instant createdAt);
}
