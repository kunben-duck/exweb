/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.routing;

/**
 * 将意图服务返回的规范化 accessName 识别为 Relay 专家角色。
 *
 * <p>专家前缀只负责分类并且只移除一次；前缀后的内容原样作为 roleName，避免业务层感知
 * Relay 的具体角色命名规则。</p>
 */
public final class DomainExpertAccessNameResolver {
    private final String prefix;

    public DomainExpertAccessNameResolver(String prefix) {
        this.prefix = normalize(prefix);
    }

    public Resolution resolve(String accessName) {
        String normalized = normalize(accessName);
        if (normalized == null || prefix == null || !normalized.startsWith(prefix)) {
            return Resolution.notDomainExpert();
        }
        String roleName = normalize(normalized.substring(prefix.length()));
        return new Resolution(true, roleName);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** 专家匹配结果；matched=true且roleName为空表示前缀命中但协议值不完整。 */
    public record Resolution(boolean matched, String roleName) {
        private static Resolution notDomainExpert() {
            return new Resolution(false, null);
        }

        public boolean validDomainExpert() {
            return matched && roleName != null;
        }

        public boolean malformedDomainExpert() {
            return matched && roleName == null;
        }
    }
}
