/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Message creation channel; Run metadata carries it across asynchronous terminal commits. */
public final class MessageChannel {
    public static final String RUN_METADATA_KEY = "_messageChannel";

    private MessageChannel() {
    }

    public static String fromRequest(String channel) {
        String value = channel == null || channel.isBlank() ? "web" : channel.trim();
        if (value.length() > 64) {
            throw new IllegalArgumentException("channel 长度不能超过 64");
        }
        return value;
    }

    public static String fromRun(ChatRun run) {
        Object value = run == null ? null : run.metadata().get(RUN_METADATA_KEY);
        return value instanceof String text && !text.isBlank() && text.length() <= 64 ? text : null;
    }

    public static Map<String, Object> removeReserved(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        if (!metadata.containsKey(RUN_METADATA_KEY)) {
            return metadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove(RUN_METADATA_KEY);
        return ChatPayloadMaps.immutableCopy(copy);
    }

    public static Map<String, Object> forRun(Map<String, Object> metadata, String channel) {
        Map<String, Object> copy = new LinkedHashMap<>(removeReserved(metadata));
        if (channel != null) {
            copy.put(RUN_METADATA_KEY, fromRequest(channel));
        }
        return ChatPayloadMaps.immutableCopy(copy);
    }
}
