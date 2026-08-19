package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Map;

class MessageSkillMetadataTest {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MessageSkillMetadata metadata = new MessageSkillMetadata(objectMapper);

    @Test
    void replacePreservesOtherKeysAndUsesOnlyCurrentRunSkillId() throws Exception {
        MessageSkillMetadata.MergeResult result = metadata.replace(
                "{\"migration\":true,\"skillIds\":[\"skill-old\"]}",
                "skill-b");

        assertThat(result.changed()).isTrue();
        assertThat(result.invalidExistingMetadata()).isFalse();
        assertThat(objectMapper.readValue(result.metadataJson(), MAP_TYPE))
                .containsEntry("migration", true)
                .containsEntry("skillId", "skill-b")
                .doesNotContainKey("skillIds");
    }

    @Test
    void malformedMetadataIsLeftUntouched() {
        MessageSkillMetadata.MergeResult result = metadata.replace("not-json", "skill-a");

        assertThat(result.metadataJson()).isEqualTo("not-json");
        assertThat(result.changed()).isFalse();
        assertThat(result.invalidExistingMetadata()).isTrue();
    }

    @Test
    void incompatibleExistingSkillFieldsAreReplacedWithoutChangingOtherKeys() throws Exception {
        String original = "{\"migration\":true,\"skillIds\":{\"unexpected\":true}}";

        MessageSkillMetadata.MergeResult result = metadata.replace(original, "skill-a");

        assertThat(result.changed()).isTrue();
        assertThat(result.invalidExistingMetadata()).isFalse();
        assertThat(objectMapper.readValue(result.metadataJson(), MAP_TYPE))
                .containsEntry("migration", true)
                .containsEntry("skillId", "skill-a")
                .doesNotContainKey("skillIds");
    }

    @Test
    void emptyCurrentRunRemovesNewAndLegacySkillFields() throws Exception {
        MessageSkillMetadata.MergeResult result = metadata.replace(
                "{\"finishReason\":\"USER_STOP\",\"skillId\":\"skill-a\","
                        + "\"skillIds\":[\"skill-old\"]}",
                null);

        assertThat(objectMapper.readValue(result.metadataJson(), MAP_TYPE))
                .containsEntry("finishReason", "USER_STOP")
                .doesNotContainKey("skillId")
                .doesNotContainKey("skillIds");
    }

    @Test
    void matchingSingleSkillLeavesMetadataUnchanged() {
        String original = "{\"finishReason\":\"STOP\",\"skillId\":\"skill-a\"}";

        MessageSkillMetadata.MergeResult result = metadata.replace(original, "skill-a");

        assertThat(result.metadataJson()).isEqualTo(original);
        assertThat(result.changed()).isFalse();
    }
}
