package com.huawei.finance.front.one.application.integration.usecase;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;
import java.util.Map;

/**
 * 用例库匹配请求。
 *
 * <p>用例库只负责返回业务样例命中情况和推荐 DomainAgent，不直接执行任务。</p>
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 前端聊天会话标识。
 * @param message 本轮用户输入文本。
 * @param attachments 本轮关联附件引用。
 * @param memoryContext SuperAgent 可选记忆上下文；长短期记忆关闭时为空上下文。
 * @param metadata 前端或上游传入的扩展元数据。
 */
public record UseCaseMatchRequest(
        String tenantId,
        String userId,
        String sessionId,
        String message,
        List<AttachmentRef> attachments,
        MemoryContext memoryContext,
        Map<String, Object> metadata
) {
    public UseCaseMatchRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
