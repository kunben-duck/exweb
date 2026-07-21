package com.huawei.it.ex.one.common.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Chat JSON payload 的不可变浅复制工具。
 *
 * <p>JSON 对象允许字段值为 {@code null}，而 {@link Map#copyOf(Map)} 会拒绝 null value。
 * ChatEvent、message part 和 Interaction payload 使用该方法保留合法 JSON null，同时继续拒绝
 * 无法可靠序列化为 JSON object 的 null key。</p>
 */
public final class ChatPayloadMaps {
    private ChatPayloadMaps() {
    }

    public static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "Chat payload key cannot be null"), value));
        return Collections.unmodifiableMap(copy);
    }
}
