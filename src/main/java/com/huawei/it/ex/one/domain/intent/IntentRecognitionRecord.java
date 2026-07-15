package com.huawei.it.ex.one.domain.intent;

import java.time.Instant;

/**
 * 一次意图识别调用的统计和排障记录。
 *
 * <p>该记录是旁路事实，不参与聊天路由决策。它保存调用输入、识别候选、最终采纳结果和必要原始
 * 响应摘要，便于后续评估意图识别准确率。</p>
 *
 * @param id 记录主键。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 会话标识。
 * @param runId 本轮 run 标识。
 * @param commandId 前端命令标识。
 * @param queryText 本轮用户问题文本，已按配置截断。
 * @param queryHash 本轮用户问题 SHA-256 摘要。
 * @param status 识别记录状态，例如 SUCCESS、NO_MATCH、FAILED、DEGRADED。
 * @param intentId 命中的意图 ID。
 * @param intentName 命中的意图名称。
 * @param resourceId 意图服务推荐的技能/资源 ID。
 * @param confidence 命中候选置信度。
 * @param source 意图服务返回的来源，例如 llm。
 * @param candidateCount 候选项数量。
 * @param confidenceThreshold 本次路由使用的置信度阈值。
 * @param accepted 是否被最终路由采纳。
 * @param routeType 最终路由类型。
 * @param routeAgentCode 最终路由选中的 agent/skill 编码。
 * @param routeReason 最终路由原因。
 * @param resultMessage 意图服务返回的解释文本。
 * @param itemsJson 意图服务候选 items JSON，已按配置截断。
 * @param rawResponseJson 意图服务原始响应 JSON，已按配置截断。
 * @param errorMessage 错误或降级原因。
 * @param latencyMs 意图服务调用耗时，单位毫秒。
 * @param createdAt 记录创建时间。
 */
public record IntentRecognitionRecord(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String commandId,
        String queryText,
        String queryHash,
        String status,
        String intentId,
        String intentName,
        String resourceId,
        Double confidence,
        String source,
        Integer candidateCount,
        Double confidenceThreshold,
        Boolean accepted,
        String routeType,
        String routeAgentCode,
        String routeReason,
        String resultMessage,
        String itemsJson,
        String rawResponseJson,
        String errorMessage,
        Long latencyMs,
        Instant createdAt
) {
}
