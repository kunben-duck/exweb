package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionRecordRepository;
import com.huawei.it.ex.one.domain.intent.IntentRecognitionRecord;

import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 意图识别记录数据库实现。
 *
 * <p>该 repository 只负责插入一条记录；异步、失败吞吐和字段裁剪由 application service 控制。</p>
 */
@Repository
public class MyBatisIntentRecognitionRecordRepository implements IntentRecognitionRecordRepository {
    private final IntentRecognitionRecordMapper mapper;

    public MyBatisIntentRecognitionRecordRepository(IntentRecognitionRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(IntentRecognitionRecord record) {
        mapper.insert(new IntentRecognitionRecordWriteRow(
                record.id(),
                record.tenantId(),
                record.userId(),
                record.sessionId(),
                record.runId(),
                record.commandId(),
                record.queryText(),
                record.queryHash(),
                record.status(),
                record.intentId(),
                record.intentName(),
                record.resourceId(),
                record.confidence(),
                record.source(),
                record.candidateCount(),
                record.confidenceThreshold(),
                record.accepted(),
                record.routeType(),
                record.routeAgentCode(),
                record.routeReason(),
                record.resultMessage(),
                record.itemsJson(),
                record.rawResponseJson(),
                record.errorMessage(),
                record.latencyMs(),
                record.createdAt()
        ));
    }

    @Override
    public Optional<String> findLatestRecognizedIntentName(
            String tenantId, String userId, String sessionId, String runId) {
        return Optional.ofNullable(mapper.findLatestRecognizedIntentName(
                tenantId, userId, sessionId, runId));
    }
}
