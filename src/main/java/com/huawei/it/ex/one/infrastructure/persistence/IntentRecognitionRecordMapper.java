package com.huawei.it.ex.one.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    /**
     * 查询一个可信user消息所属run最近记录的有效Intent名称。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param runId user消息关联的run标识。
     * @return 最近一次SUCCESS或NO_MATCH记录的Intent名称；不存在时为{@code null}。
     */
    String findLatestRecognizedIntentName(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("runId") String runId);
}
