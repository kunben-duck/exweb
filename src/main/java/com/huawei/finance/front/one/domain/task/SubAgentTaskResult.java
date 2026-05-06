package com.huawei.finance.front.one.domain.task;

import java.util.List;
import java.util.Map;

/**
 * SubAgent 原始响应标准化后的统一结果。
 *
 * @param message 面向用户展示的回复文本。
 * @param taskStatus SuperAgent 用于路由和任务保持的状态。
 * @param rawNormalizedStatus 响应标准化器识别出的原始状态。
 * @param requiredInputs 等待用户补充的信息列表。
 * @param agentSessionId 下游 SubAgent 返回的会话标识。
 * @param businessObjectRefs 下游业务对象引用。
 * @param confidence 状态识别置信度，范围 0 到 1。
 * @param confirmationQuestion 状态不明时面向用户的澄清问题。
 * @param raw 原始响应或标准化诊断信息。
 */
public record SubAgentTaskResult(
        String message,
        TaskStatus taskStatus,
        TaskStatus rawNormalizedStatus,
        List<RequiredInput> requiredInputs,
        String agentSessionId,
        List<BusinessObjectRef> businessObjectRefs,
        double confidence,
        String confirmationQuestion,
        Map<String, Object> raw
) {
    public SubAgentTaskResult {
        taskStatus = taskStatus == null ? TaskStatus.WAITING_USER_CONFIRMATION : taskStatus;
        rawNormalizedStatus = rawNormalizedStatus == null ? taskStatus : rawNormalizedStatus;
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        businessObjectRefs = businessObjectRefs == null ? List.of() : List.copyOf(businessObjectRefs);
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    /**
     * 创建失败结果。
     *
     * @param message 失败说明。
     * @return 标准失败结果。
     */
    public static SubAgentTaskResult failed(String message) {
        return new SubAgentTaskResult(message, TaskStatus.FAILED, TaskStatus.FAILED, List.of(), null, List.of(),
                1.0, null, Map.of());
    }
}
