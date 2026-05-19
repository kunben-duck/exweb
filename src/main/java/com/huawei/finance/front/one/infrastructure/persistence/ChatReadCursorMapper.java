package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * fin_ex_chat_read_cursor_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatReadCursorMapper {
    /**
     * 单调写入用户在会话中的最大已消费 seq。
     *
     * <p>{@code GREATEST} 是这里的关键约束：多设备 ack 可能乱序到达，较小 seq 不能覆盖较大 seq。</p>
     *
     * @param id 新建记录时使用的主键；已存在记录会保留原主键。
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param lastConsumedSeq 客户端已经处理完成的最大事件序号。
     * @param updatedAt 本次写入时间。
     * @return 写入后的数据库行。
     */
    @Insert("""
            INSERT INTO fin_ex_chat_read_cursor_t(
                id, tenant_id, user_id, session_id, last_consumed_seq, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{lastConsumedSeq}, #{updatedAt}
            )
            """)
    int insert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("lastConsumedSeq") long lastConsumedSeq,
            @Param("updatedAt") Instant updatedAt
    );

    @Update("""
            UPDATE fin_ex_chat_read_cursor_t
            SET last_consumed_seq = CASE
                    WHEN #{lastConsumedSeq} > last_consumed_seq THEN #{lastConsumedSeq}
                    ELSE last_consumed_seq
                END,
                updated_at = CASE
                    WHEN #{lastConsumedSeq} >= last_consumed_seq THEN #{updatedAt}
                    ELSE updated_at
                END
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
            """)
    int updateMax(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("lastConsumedSeq") long lastConsumedSeq,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 查询用户在会话中的已消费游标。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 数据库行；不存在时返回 null。
     */
    @Select("""
            SELECT id, tenant_id, user_id, session_id, last_consumed_seq, updated_at
            FROM fin_ex_chat_read_cursor_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
            """)
    @Results(id = "chatReadCursorResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "last_consumed_seq", property = "lastConsumedSeq"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ChatReadCursorRow find(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId
    );
}
