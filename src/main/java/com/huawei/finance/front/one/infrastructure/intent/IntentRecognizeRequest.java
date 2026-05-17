package com.huawei.finance.front.one.infrastructure.intent;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;
import java.util.Map;

/**
 * 发送给第三方意图服务的请求 DTO。
 *
 * <p>DTO 放在 infra 层，避免把外部 HTTP 契约反向污染 application/domain。</p>
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 前端聊天会话标识。
 * @param message 本轮用户输入文本。
 * @param attachments 本轮关联附件引用。
 * @param metadata 前端或上游扩展元数据。
 * @param memory SuperAgent 可选记忆上下文；长短期记忆关闭时为空上下文。
 */
public record IntentRecognizeRequest(
        String tenantId,
        String userId,
        String sessionId,
        String message,
        List<AttachmentRef> attachments,
        Map<String, Object> metadata,
        MemoryContext memory
) {}
