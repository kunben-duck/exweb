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
 * <p>写入时通过 fin_ex_chat_session_t 反查 tenant/user，保证事件事实源和会话归属一致。</p>
 */
@Mapper
public interface ChatEventMapper {
    @Select("SELECT nextval('fin_ex_chat_event_seq')")
    Long nextSeq();

    @Insert("""
            INSERT INTO fin_ex_chat_event_t(
                id, tenant_id, user_id, session_id, run_id, seq, event_type, payload_json, created_at
            )
            SELECT #{id}, tenant_id, user_id, #{sessionId}, #{runId}, #{seq}, #{eventType}, #{payloadJson}, #{createdAt}
            FROM fin_ex_chat_session_t
            WHERE id = #{sessionId}
            """)
    int insertFromSession(
            @Param("id") String id,
            @Param("sessionId") String sessionId,
            @Param("runId") String runId,
            @Param("seq") long seq,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson,
            @Param("createdAt") Instant createdAt
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, run_id, seq, event_type, payload_json, created_at
            FROM fin_ex_chat_event_t
            WHERE id = #{id}
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
    ChatEventRow findById(@Param("id") String id);

    @Select("""
            SELECT id, tenant_id, user_id, session_id, run_id, seq, event_type, payload_json, created_at
            FROM fin_ex_chat_event_t
            WHERE session_id = #{sessionId}
              AND seq > #{afterSeq}
            ORDER BY seq ASC
            """)
    @ResultMap("chatEventResultMap")
    List<ChatEventRow> findBySessionIdAndAfterSeq(
            @Param("sessionId") String sessionId,
            @Param("afterSeq") long afterSeq
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, run_id, seq, event_type, payload_json, created_at
            FROM fin_ex_chat_event_t
            WHERE run_id = #{runId}
              AND seq > #{afterSeq}
            ORDER BY seq ASC
            """)
    @ResultMap("chatEventResultMap")
    List<ChatEventRow> findByRunIdAndAfterSeq(
            @Param("runId") String runId,
            @Param("afterSeq") long afterSeq
    );

    @Select("""
            SELECT COALESCE(MAX(seq), 0)
            FROM fin_ex_chat_event_t
            WHERE session_id = #{sessionId}
            """)
    long findLatestSeqBySessionId(@Param("sessionId") String sessionId);
}
