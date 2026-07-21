package com.huawei.it.ex.one.runtime.application.model;

import java.util.List;
import java.util.Map;

/** Minimal immutable Chat command input required by Runtime execution. */
public record RuntimeCommandSnapshot(
        String sessionId,
        String message,
        List<RuntimeAttachmentSnapshot> attachments,
        Map<String, Object> metadata
) {
    public RuntimeCommandSnapshot {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
