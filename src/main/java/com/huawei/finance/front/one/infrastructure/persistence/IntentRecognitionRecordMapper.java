package com.huawei.finance.front.one.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * fin_ex_intent_recognition_t 的 MyBatis Mapper。
 */
@Mapper
public interface IntentRecognitionRecordMapper {
    @Insert("""
            INSERT INTO fin_ex_intent_recognition_t(
                id, tenant_id, user_id, session_id, run_id, command_id, query_text, query_hash,
                status, intent_id, intent_name, resource_id, confidence, source, candidate_count,
                confidence_threshold, accepted, route_type, route_agent_code, route_reason,
                result_message, items_json, raw_response_json, error_message, latency_ms, created_at
            )
            VALUES(
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{runId}, #{commandId}, #{queryText}, #{queryHash},
                #{status}, #{intentId}, #{intentName}, #{resourceId}, #{confidence}, #{source}, #{candidateCount},
                #{confidenceThreshold}, #{accepted}, #{routeType}, #{routeAgentCode}, #{routeReason},
                #{resultMessage}, #{itemsJson}, #{rawResponseJson}, #{errorMessage}, #{latencyMs}, #{createdAt}
            )
            """)
    void insert(IntentRecognitionRecordWriteRow row);
}
