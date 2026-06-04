package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_runtime_raw_stream_log_t 的 MyBatis Mapper。
 */
@Mapper
public interface RuntimeRawStreamLogMapper {
    @Insert("""
            INSERT INTO fin_ex_runtime_raw_stream_log_t(
                id, tenant_id, user_id, session_id, run_id, runtime_provider, api_adapter,
                chunk_index, raw_content, raw_content_hash, content_length, source_content_length,
                chunk_count, split_part_index, split_part_count, truncated, terminal, created_at
            )
            VALUES(
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{runId}, #{runtimeProvider}, #{apiAdapter},
                #{chunkIndex}, #{rawContent}, #{rawContentHash}, #{contentLength}, #{sourceContentLength},
                #{chunkCount}, #{splitPartIndex}, #{splitPartCount}, #{truncated}, #{terminal}, #{createdAt}
            )
            """)
    void insert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("runId") String runId,
            @Param("runtimeProvider") String runtimeProvider,
            @Param("apiAdapter") String apiAdapter,
            @Param("chunkIndex") long chunkIndex,
            @Param("rawContent") String rawContent,
            @Param("rawContentHash") String rawContentHash,
            @Param("contentLength") int contentLength,
            @Param("sourceContentLength") int sourceContentLength,
            @Param("chunkCount") int chunkCount,
            @Param("splitPartIndex") int splitPartIndex,
            @Param("splitPartCount") int splitPartCount,
            @Param("truncated") boolean truncated,
            @Param("terminal") boolean terminal,
            @Param("createdAt") Instant createdAt
    );
}
