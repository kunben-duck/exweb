package com.huawei.it.ex.one.common.logging;

import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import java.util.Map;

/**
 * Deterministic logfmt renderer used by logging backend adapters.
 */
final class SystemErrorLogFormatter {
    private SystemErrorLogFormatter() {
    }

    static String format(SystemErrorLogEntry event, Throwable throwable) {
        StringBuilder output = new StringBuilder(256);
        append(output, "errorCode", event.error().code());
        append(output, "reasonCode", event.error().reasonCode());
        append(output, "message", event.message());
        append(output, "component", "chatservice");
        append(output, "origin", event.error().origin());
        append(output, "retryable", event.retryable());
        appendOptional(output, "traceId", event.traceId());
        appendOptional(output, "runId", event.runId());
        appendOptional(output, "sessionId", event.sessionId());
        appendOptional(output, "operation", event.operation());
        if (event.durationMs() != null) {
            append(output, "durationMs", event.durationMs());
        }
        appendOptional(output, "legacyCode", event.legacyCode());
        appendOptional(output, "upstreamErrorCode", event.upstreamErrorCode());
        if (throwable != null) {
            append(output, "exceptionClass", throwable.getClass().getName());
        }
        for (Map.Entry<String, Object> entry : event.attributes().entrySet()) {
            append(output, entry.getKey(), entry.getValue());
        }
        return output.toString();
    }

    private static void appendOptional(StringBuilder output, String key, String value) {
        if (value != null && !value.isBlank()) {
            append(output, key, value);
        }
    }

    private static void append(StringBuilder output, String key, Object value) {
        if (!output.isEmpty()) {
            output.append(' ');
        }
        output.append(key).append('=');
        if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
            return;
        }
        output.append('"').append(escape(String.valueOf(value))).append('"');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
