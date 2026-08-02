package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class SessionTitleMetadataTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesExistingMetadataAndMarksManualTitle() throws Exception {
        SessionTitleMetadata metadata = metadata(true);

        String initialized = metadata.initialize("{\"existing\":1}", SessionTitleSummarySource.DEFAULT);
        String marked = metadata.markUser(initialized);
        JsonNode root = objectMapper.readTree(marked);

        assertThat(root.path("existing").asInt()).isEqualTo(1);
        assertThat(root.path(SessionTitleMetadata.METADATA_KEY).path("source").asText()).isEqualTo("USER");
    }

    @Test
    void disabledFeatureAndInvalidLegacyMetadataRemainUntouched() {
        assertThat(metadata(false).initialize(null, SessionTitleSummarySource.AUTO)).isNull();
        assertThat(metadata(true).markUser("not-json")).isEqualTo("not-json");
    }

    private SessionTitleMetadata metadata(boolean enabled) {
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(enabled);
        return new SessionTitleMetadata(objectMapper, properties);
    }
}
