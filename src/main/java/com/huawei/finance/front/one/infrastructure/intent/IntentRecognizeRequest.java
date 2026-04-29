package com.huawei.finance.front.one.infrastructure.intent;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;
import java.util.Map;

/**
 * 发送给第三方意图服务的请求 DTO。
 *
 * <p>DTO 放在 infra 层，避免把外部 HTTP 契约反向污染 application/domain。</p>
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
