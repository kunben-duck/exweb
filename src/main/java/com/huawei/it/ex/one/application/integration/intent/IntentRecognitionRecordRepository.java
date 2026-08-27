package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.intent.IntentRecognitionRecord;

import java.util.Optional;

/**
 * 意图识别记录仓储端口。
 *
 * <p>该端口只服务统计和排障旁路。实现层写入失败不能影响聊天 run 主链路。</p>
 */
public interface IntentRecognitionRecordRepository {
    /**
     * 保存一次意图识别记录。
     *
     * @param record 意图识别记录。
     */
    void save(IntentRecognitionRecord record);

    /**
     * Finds the latest successful or no-match Intent name associated with one trusted user message run.
     * The default keeps lightweight test repositories source compatible.
     */
    default Optional<String> findLatestRecognizedIntentName(
            String tenantId, String userId, String sessionId, String runId) {
        return Optional.empty();
    }
}
