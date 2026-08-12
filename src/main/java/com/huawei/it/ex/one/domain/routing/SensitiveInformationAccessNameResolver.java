package com.huawei.it.ex.one.domain.routing;

/** 识别需要交给 Relay Delegate 处理的敏感信息意图 accessName。 */
public final class SensitiveInformationAccessNameResolver {
    private final String accessName;

    public SensitiveInformationAccessNameResolver(String accessName) {
        this.accessName = normalize(accessName);
    }

    public boolean matches(String candidateAccessName) {
        String candidate = normalize(candidateAccessName);
        return accessName != null && accessName.equals(candidate);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
