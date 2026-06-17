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

/**
 * fin_ex_chat_event_t 的 MyBatis Mapper。
 *
 * <p>写入时同时校验 session 与 run 的 tenant/user/session 归属，保证事件事实源不会被
 * 错误 runId 或 sessionId 污染。</p>
 */
@Mapper
public interface ChatEventMapper {
    @Select("SELECT nextval('fin_ex_chat_event_seq')")
    Long nextSeq();

    @Insert("""
            INSERT INTO fin_ex_chat_event_t(
                id, tenant_id, user_id, session_id, run_id, seq, event_type, payload_json, created_at
            )
            SELECT #{id}, s.tenant_id, s.user_id, s.id, r.id, #{seq}, #{eventType}, #{payloadJson}, #{createdAt}
            FROM fin_ex_chat_session_t s
            JOIN fin_ex_chat_run_t r
              ON r.id = #{runId}
             AND r.session_id = s.id
             AND r.tenant_id = s.tenant_id
             AND r.user_id = s.user_id
            WHERE s.id = #{sessionId}
            """)
    int insertFromSession(ChatEventWriteRow row);

    @Insert("""
            INSERT INTO fin_ex_chat_event_t(
                id, tenant_id, user_id, session_id, run_id, seq, event_type, payload_json, created_at
            )
            SELECT #{id}, s.tenant_id, s.user_id, s.id, r.id, #{seq}, #{eventType}, #{payloadJson}, #{createdAt}
            FROM fin_ex_chat_session_t s
            JOIN fin_ex_chat_run_t r
              ON r.id = #{runId}
             AND r.session_id = s.id
             AND r.tenant_id = s.tenant_id
             AND r.user_id = s.user_id
             AND r.status = 'RUNNING'
            JOIN fin_ex_chat_run_execution_t e
              ON e.run_id = r.id
             AND e.tenant_id = r.tenant_id
             AND e.user_id = r.user_id
             AND e.session_id = r.session_id
             AND e.owner_instance_id = #{ownerInstanceId}
             AND e.fencing_token = #{fencingToken}
             AND e.execution_status = 'RUNNING'
            WHERE s.id = #{sessionId}
            """)
    int insertFromSessionWithExecutionGuard(ChatEventWriteRow row);

    @Select("""
            SELECT id, tenant_id, user_id, session_id, run_id, seq, event_type, payload_json, created_at
            FROM fin_ex_chat_event_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND seq > #{afterSeq}
            ORDER BY seq ASC
            """)
    @Results(id = "chatEventResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "payload_json", property = "payloadJson"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatEventRow> findByOwnerAndSessionAfterSeq(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("afterSeq") long afterSeq
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, run_id, seq, event_type, payload_json, created_at
            FROM fin_ex_chat_event_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND run_id = #{runId}
              AND seq > #{afterSeq}
            ORDER BY seq ASC
            """)
    @ResultMap("chatEventResultMap")
    List<ChatEventRow> findByOwnerAndRunAfterSeq(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("runId") String runId,
            @Param("afterSeq") long afterSeq
    );

    @Select("""
            SELECT COALESCE(MAX(seq), 0)
            FROM fin_ex_chat_event_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
            """)
    long findLatestSeqByOwnerAndSession(@Param("tenantId") String tenantId,
                                        @Param("userId") String userId,
                                        @Param("sessionId") String sessionId);
}
