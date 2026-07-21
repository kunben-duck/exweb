package com.huawei.it.ex.one.intent.application.model;

/** Immutable attachment facts consumed by intent routing and its compatibility adapter. */
public record IntentAttachmentSnapshot(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes,
        Long tokenSize,
        String source
) {
}
