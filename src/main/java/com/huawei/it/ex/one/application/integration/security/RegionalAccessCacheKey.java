package com.huawei.it.ex.one.application.integration.security;

/**
 * Local regional decision cache key. Values never leave the current JVM.
 */
public record RegionalAccessCacheKey(String ownerUserId, String employeeNumber, String ipAddress) {
    public RegionalAccessCacheKey {
        ownerUserId = safe(ownerUserId);
        employeeNumber = safe(employeeNumber);
        ipAddress = safe(ipAddress);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
