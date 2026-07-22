package com.huawei.it.ex.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

class RelayRuntimeWireRequestMapperTest {
    @Test
    void sensitiveMetadataFilteringDoesNotDependOnDefaultLocale() {
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            Map<String, Object> result = RelayRuntimeWireRequestMapper.sanitizedMetadata(Map.of(
                    "AUTHORIZATION", "secret",
                    "COOKIE", "secret",
                    "TOKEN", "secret",
                    "traceId", "spoofed",
                    "clientTraceId", "client-visible",
                    "safe", "visible"
            ));

            assertThat(result)
                    .hasSize(2)
                    .containsEntry("safe", "visible")
                    .containsEntry("clientTraceId", "client-visible")
                    .doesNotContainKeys("AUTHORIZATION", "COOKIE", "TOKEN", "traceId");
        } finally {
            Locale.setDefault(previousLocale);
        }
    }
}
