package com.huawei.it.ex.one.runtime.application.model;

import java.time.Instant;

/** Immutable document fact passed to a Runtime after Chat has completed authorization. */
public record RuntimeDocumentSnapshot(
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
        String source,
        Long tokenSize,
        String metadataJson,
        Instant createdAt,
        Instant updatedAt
) {
}
