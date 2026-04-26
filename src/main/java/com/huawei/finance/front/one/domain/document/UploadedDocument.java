package com.huawei.finance.front.one.domain.document;

import java.time.Instant;

public record UploadedDocument(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String originalName,
        String bucket,
        String objectKey,
        String contentType,
        long sizeBytes,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
