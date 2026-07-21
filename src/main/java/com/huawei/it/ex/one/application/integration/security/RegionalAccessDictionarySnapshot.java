package com.huawei.it.ex.one.application.integration.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Current in-memory employee whitelist and EU country-name snapshot.
 */
public record RegionalAccessDictionarySnapshot(
        Set<String> employeeWhitelist,
        Set<String> euCountryNames
) {
    public RegionalAccessDictionarySnapshot {
        employeeWhitelist = immutableValues(employeeWhitelist);
        euCountryNames = immutableValues(euCountryNames);
    }

    public static RegionalAccessDictionarySnapshot empty() {
        return new RegionalAccessDictionarySnapshot(Set.of(), Set.of());
    }

    private static Set<String> immutableValues(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(copy::add);
        return copy.isEmpty() ? Set.of() : Collections.unmodifiableSet(copy);
    }
}
