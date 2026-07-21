package com.huawei.it.ex.one.intent.application.model;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import java.time.Instant;

/**
 * 意图识别记录的不可变运行期快照。
 *
 * <p>该对象在 Servlet 请求/后台 run 编排线程中创建，然后交给异步线程写库。异步线程只消费这里
 * 固化的字段，不读取身份解析组件、企业 ThreadLocal 或 HTTP request。</p>
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 会话标识。
 * @param runId 本轮 run 标识。
 * @param commandId 前端命令标识。
 * @param queryText 本轮用户问题。
 * @param intentDecision 意图识别结果。
 * @param routeTarget 最终路由结果。
 * @param confidenceThreshold 本次路由使用的置信度阈值。
 * @param latencyMs 意图服务调用耗时，单位毫秒。
 * @param createdAt 快照创建时间。
 */
public record IntentRecognitionRecordSnapshot(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String commandId,
        String queryText,
        IntentDecision intentDecision,
        RouteTarget routeTarget,
        double confidenceThreshold,
        Long latencyMs,
        Instant createdAt
) {
    public static IntentRecognitionRecordSnapshot of(IntentRecognitionRecordInput input) {
        if (input == null) {
            return new IntentRecognitionRecordSnapshot(
                    null, null, null, null, null, null, null, null, 0.0, null, Instant.now());
        }
        UserContext user = input.user();
        IntentCommandSnapshot command = input.command();
        return new IntentRecognitionRecordSnapshot(
                user == null ? null : user.tenantId(),
                user == null ? null : user.ownerUserId(),
                command == null ? null : command.sessionId(),
                input.runId(),
                command == null ? null : command.commandId(),
                command == null ? null : command.message(),
                input.intentDecision(),
                input.routeTarget(),
                input.confidenceThreshold(),
                input.latencyMs(),
                Instant.now()
        );
    }

    /**
     * 构造意图记录快照所需的请求期输入。
     */
    public record IntentRecognitionRecordInput(UserContext user, IntentCommandSnapshot command, String runId,
                                               IntentDecision intentDecision, RouteTarget routeTarget,
                                               double confidenceThreshold, Long latencyMs) {
    }
}
