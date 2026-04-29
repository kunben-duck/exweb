package com.huawei.finance.front.one.infrastructure.memory.mybatis;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * fin_ex_conversation_summary_t 的 MyBatis Mapper。
 *
 * <p>摘要写入时通过会话表补齐 tenant/user，避免摘要表和会话归属不一致。</p>
 */
@Mapper
public interface ConversationSummaryMapper {
    @Insert("""
            INSERT INTO fin_ex_conversation_summary_t(
                id, tenant_id, user_id, session_id, summary_text, message_from_seq, message_to_seq, created_at
            )
            SELECT #{id}, tenant_id, user_id, #{sessionId}, #{summaryText}, #{messageFromSeq}, #{messageToSeq}, #{createdAt}
            FROM fin_ex_chat_session_t
            WHERE id = #{sessionId}
            """)
    int insertFromSession(
            @Param("id") String id,
            @Param("sessionId") String sessionId,
            @Param("summaryText") String summaryText,
            @Param("messageFromSeq") Long messageFromSeq,
            @Param("messageToSeq") Long messageToSeq,
            @Param("createdAt") Instant createdAt
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, summary_text, message_from_seq, message_to_seq, created_at
            FROM fin_ex_conversation_summary_t
            WHERE session_id = #{sessionId}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    @Results(id = "conversationSummaryResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "summary_text", property = "summaryText"),
            @Result(column = "message_from_seq", property = "messageFromSeq"),
            @Result(column = "message_to_seq", property = "messageToSeq"),
            @Result(column = "created_at", property = "createdAt")
    })
    ConversationSummaryRow findLatestBySessionId(@Param("sessionId") String sessionId);
}
