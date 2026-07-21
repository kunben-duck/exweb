package com.huawei.it.ex.one.intent.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

/**
 * fin_ex_intent_recognition_t 的 MyBatis Mapper。
 */
@Mapper
public interface IntentRecognitionRecordMapper {
    /**
     * 写入一次意图识别记录，用于离线统计识别准确率和排查路由决策。
     *
     * @param row 意图识别记录写入行，包含请求上下文、输入摘要、识别结果、采纳结果和原始响应摘要。
     */
    void insert(IntentRecognitionRecordWriteRow row);
}
