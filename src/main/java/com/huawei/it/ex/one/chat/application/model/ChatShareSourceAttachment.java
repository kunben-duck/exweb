package com.huawei.it.ex.one.chat.application.model;

/** Immutable attachment projection exposed to the share context. */
public record ChatShareSourceAttachment(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes
) {
}
