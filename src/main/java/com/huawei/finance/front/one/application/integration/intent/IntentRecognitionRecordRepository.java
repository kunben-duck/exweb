package com.huawei.finance.front.one.application.integration.intent;

import com.huawei.finance.front.one.domain.intent.IntentRecognitionRecord;

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
}
