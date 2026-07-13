package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
                    "safe", "visible"
            ));

            assertThat(result)
                    .hasSize(1)
                    .containsEntry("safe", "visible")
                    .doesNotContainKeys("AUTHORIZATION", "COOKIE", "TOKEN");
        } finally {
            Locale.setDefault(previousLocale);
        }
    }
}
