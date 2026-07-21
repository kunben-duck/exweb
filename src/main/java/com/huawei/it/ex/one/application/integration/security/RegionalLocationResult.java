package com.huawei.it.ex.one.application.integration.security;

/**
 * Result of one regional location lookup.
 */
public record RegionalLocationResult(Status status, String countryName) {
    public enum Status {
        FOUND,
        NOT_APPLICABLE,
        UNAVAILABLE
    }

    public RegionalLocationResult {
        status = status == null ? Status.UNAVAILABLE : status;
        countryName = countryName == null ? null : countryName.trim();
    }

    public static RegionalLocationResult found(String countryName) {
        if (countryName == null || countryName.isBlank()) {
            return unavailable();
        }
        return new RegionalLocationResult(Status.FOUND, countryName);
    }

    public static RegionalLocationResult notApplicable() {
        return new RegionalLocationResult(Status.NOT_APPLICABLE, null);
    }

    public static RegionalLocationResult unavailable() {
        return new RegionalLocationResult(Status.UNAVAILABLE, null);
    }

    public boolean found() {
        return status == Status.FOUND && countryName != null && !countryName.isBlank();
    }
}
