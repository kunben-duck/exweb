package com.huawei.it.ex.one.runtime.application.model;

/** Immutable attachment reference owned by the Runtime application boundary. */
public record RuntimeAttachmentSnapshot(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes,
        Long tokenSize,
        String source
) {
}
