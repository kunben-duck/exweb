package com.huawei.finance.front.one.application.integration.usecase;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;
import java.util.Map;

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
