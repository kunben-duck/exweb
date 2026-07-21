package com.huawei.it.ex.one.runtime.application.model;

import java.time.Instant;

/** Recent message visible to a Runtime. */
public record RuntimeMessageSnapshot(String id, String role, String content, Instant createdAt) {
}
