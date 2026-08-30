/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable structured context for one system-error WARN or ERROR log.
 */
public final class SystemErrorLogEntry {
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
    private static final Set<String> RESERVED_ATTRIBUTES = Set.of(
            "errorcode", "reasoncode", "message", "component", "origin", "retryable",
            "traceid", "runid", "sessionid", "operation", "durationms", "legacycode",
            "upstreamerrorcode", "exceptionclass");
    private static final Set<String> SENSITIVE_TOKENS = Set.of(
            "cookie", "authorization", "token", "secret", "password", "credential",
            "apikey", "accesskey");

    private final SystemErrorCode error;
    private final String message;
    private final boolean retryable;
    private final String traceId;
    private final String runId;
    private final String sessionId;
    private final String operation;
    private final Long durationMs;
    private final String legacyCode;
    private final String upstreamErrorCode;
    private final Map<String, Object> attributes;

    private SystemErrorLogEntry(Builder builder) {
        this.error = builder.error;
        this.message = builder.message;
        this.retryable = builder.retryable;
        this.traceId = normalize(builder.traceId);
        this.runId = normalize(builder.runId);
        this.sessionId = normalize(builder.sessionId);
        this.operation = normalize(builder.operation);
        this.durationMs = builder.durationMs;
        this.legacyCode = normalize(builder.legacyCode);
        this.upstreamErrorCode = normalize(builder.upstreamErrorCode);
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    public static Builder builder(SystemErrorCode error, String message) {
        return new Builder(error, message);
    }

    public static Builder upstreamBuilder(SystemErrorCode fallback, String upstreamErrorCode, String message) {
        Objects.requireNonNull(fallback, "fallback");
        SystemErrorCode resolved = SystemErrorCode.fromRegisteredUpstreamCode(upstreamErrorCode)
                .orElse(fallback);
        Builder builder = new Builder(resolved, message);
        if (resolved == fallback && SystemErrorCode.fromRegisteredUpstreamCode(upstreamErrorCode).isEmpty()) {
            builder.upstreamErrorCode(upstreamErrorCode);
        }
        return builder;
    }

    public SystemErrorCode error() {
        return error;
    }

    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }

    public String traceId() {
        return traceId;
    }

    public String runId() {
        return runId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String operation() {
        return operation;
    }

    public Long durationMs() {
        return durationMs;
    }

    public String legacyCode() {
        return legacyCode;
    }

    public String upstreamErrorCode() {
        return upstreamErrorCode;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static final class Builder {
        private final SystemErrorCode error;
        private final String message;
        private boolean retryable;
        private String traceId;
        private String runId;
        private String sessionId;
        private String operation;
        private Long durationMs;
        private String legacyCode;
        private String upstreamErrorCode;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private Builder(SystemErrorCode error, String message) {
            this.error = Objects.requireNonNull(error, "error");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
            this.message = message.trim();
            this.retryable = error.defaultRetryable();
        }

        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }

        public Builder durationMs(long durationMs) {
            if (durationMs < 0L) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
            this.durationMs = durationMs;
            return this;
        }

        public Builder legacyCode(String legacyCode) {
            this.legacyCode = legacyCode;
            return this;
        }

        public Builder upstreamErrorCode(String upstreamErrorCode) {
            this.upstreamErrorCode = upstreamErrorCode;
            return this;
        }

        public Builder attribute(String name, Object value) {
            String normalizedName = validateAttributeName(name);
            if (value != null) {
                attributes.put(normalizedName, value);
            }
            return this;
        }

        public SystemErrorLogEntry build() {
            return new SystemErrorLogEntry(this);
        }

        private String validateAttributeName(String name) {
            if (name == null || !ATTRIBUTE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid system-error log attribute name: " + name);
            }
            String lowerCaseName = name.toLowerCase(Locale.ROOT);
            if (RESERVED_ATTRIBUTES.contains(lowerCaseName)) {
                throw new IllegalArgumentException("Reserved system-error log attribute name: " + name);
            }
            String compact = lowerCaseName.replace("_", "").replace("-", "");
            if (SENSITIVE_TOKENS.stream().anyMatch(compact::contains)) {
                throw new IllegalArgumentException("Sensitive system-error log attribute is not allowed: " + name);
            }
            return name;
        }
    }
}
