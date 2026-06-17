package com.huawei.finance.front.one.infrastructure.persistence;

import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogRepository;
import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamLogEntry;
import org.springframework.stereotype.Repository;

/**
 * Runtime 原始流响应日志数据库实现。
 *
 * <p>该 repository 只做单行插入；合并、分片、脱敏和失败吞吐由 application service 负责。</p>
 */
@Repository
public class MyBatisRuntimeRawStreamLogRepository implements RuntimeRawStreamLogRepository {
    private final RuntimeRawStreamLogMapper mapper;

    public MyBatisRuntimeRawStreamLogRepository(RuntimeRawStreamLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(RuntimeRawStreamLogEntry entry) {
        mapper.insert(new RuntimeRawStreamLogWriteRow(
                entry.id(),
                entry.tenantId(),
                entry.userId(),
                entry.sessionId(),
                entry.runId(),
                entry.runtimeProvider(),
                entry.apiAdapter(),
                entry.chunkIndex(),
                entry.rawContent(),
                entry.rawContentHash(),
                entry.contentLength(),
                entry.sourceContentLength(),
                entry.chunkCount(),
                entry.splitPartIndex(),
                entry.splitPartCount(),
                entry.truncated(),
                entry.terminal(),
                entry.createdAt()
        ));
    }
}
