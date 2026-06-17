package com.huawei.finance.front.one.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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
    void insert(RuntimeRawStreamLogWriteRow row);
}
