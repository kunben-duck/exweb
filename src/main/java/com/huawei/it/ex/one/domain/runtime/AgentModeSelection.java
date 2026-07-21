package com.huawei.it.ex.one.domain.runtime;

/**
 * Agent 模式的单个维度选择。
 *
 * @param scheme 模式维度，例如 thinking、execution 或 thinking_level。
 * @param code 该维度的取值；统一使用字符串以兼容枚举、布尔和等级语义。
 * @param displayName 可选展示名称。
 */
public record AgentModeSelection(
        String scheme,
        String code,
        String displayName
) {
    public AgentModeSelection {
        scheme = requireText(scheme, "agentMode.selections[].scheme 不能为空", 64);
        code = requireText(code, "agentMode.selections[].code 不能为空", 128);
        displayName = normalizeOptional(displayName, 256, "agentMode.selections[].displayName 长度不能超过 256");
    }

    private static String requireText(String value, String emptyMessage, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(emptyMessage.replace("不能为空", "长度不能超过 " + maxLength));
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength, String lengthMessage) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(lengthMessage);
        }
        return normalized;
    }
}
