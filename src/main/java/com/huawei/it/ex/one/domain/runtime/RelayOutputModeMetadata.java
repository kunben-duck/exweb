package com.huawei.it.ex.one.domain.runtime;

import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteTarget;

import java.util.LinkedHashMap;
import java.util.Map;

/** Trusted run metadata for Relay answer-only streaming. */
public final class RelayOutputModeMetadata {
    public static final String RUN_METADATA_KEY = "_relayAnswerStreamOnly";

    private RelayOutputModeMetadata() {
    }

    public static RelayOutputMode fromRoute(RouteTarget route) {
        return route == null || route.relayOutputMode() == null
                ? RelayOutputMode.FULL_STREAM
                : route.relayOutputMode();
    }

    public static RelayOutputMode fromRunMetadata(Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(RUN_METADATA_KEY))
                ? RelayOutputMode.ANSWER_STREAM_ONLY
                : RelayOutputMode.FULL_STREAM;
    }

    public static Map<String, Object> runMetadataOverlay(RouteTarget route) {
        return runMetadataOverlay(fromRoute(route));
    }

    public static Map<String, Object> runMetadataOverlay(RelayOutputMode outputMode) {
        return outputMode == RelayOutputMode.ANSWER_STREAM_ONLY
                ? Map.of(RUN_METADATA_KEY, true)
                : Map.of();
    }

    public static Map<String, Object> removePrivateRunMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey(RUN_METADATA_KEY)) {
            return metadata == null ? Map.of() : Map.copyOf(metadata);
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(metadata);
        sanitized.remove(RUN_METADATA_KEY);
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }
}
