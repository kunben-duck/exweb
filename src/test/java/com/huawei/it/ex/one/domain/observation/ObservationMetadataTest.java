/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

class ObservationMetadataTest {
    @Test
    void mergePreservesNullAndShallowCopyWithoutChangingInput() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("nestedNull", null);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("optionalDetail", null);
        payload.put("nested", nested);

        var result = ObservationMetadata.with(payload, context());

        assertThat(result).containsEntry("optionalDetail", null);
        assertThat(result.get("nested")).isSameAs(nested);
        assertThat(payload).doesNotContainKey(ObservationMetadata.KEY);
        payload.put("late", "not in snapshot");
        assertThat(result).doesNotContainKey("late");
        assertThatThrownBy(() -> result.put("late", "forbidden")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ObservationMetadata.read(result, "t", "u")).isEqualTo(context());
        assertThat(ObservationMetadata.read(result, "other", "u")).isNull();
        assertThat(ObservationMetadata.read(result, "t", "other")).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removalPreservesNullAndDoesNotMutateInput(boolean nullContext) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("optionalDetail", null);
        payload.put(ObservationMetadata.KEY, Map.of("traceId", "old"));

        var result = nullContext ? ObservationMetadata.with(payload, null) : ObservationMetadata.remove(payload);

        assertThat(result).containsOnlyKeys("optionalDetail").containsEntry("optionalDetail", null);
        assertThat(payload).containsKey(ObservationMetadata.KEY);
        assertThatThrownBy(() -> result.remove("optionalDetail")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void trustedContextReplacesReservedValueAndKeepsOptionalContextFieldsAbsent() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(ObservationMetadata.KEY, null);
        payload.put("optionalDetail", null);
        var result = ObservationMetadata.with(payload, context());

        assertThat(ObservationMetadata.read(result, "t", "u")).isEqualTo(context());
        assertThat(payload).containsEntry(ObservationMetadata.KEY, null);
        @SuppressWarnings("unchecked")
        var privateValue = (Map<String, Object>) result.get(ObservationMetadata.KEY);
        assertThat(privateValue).doesNotContainKeys("sourceRunId", "sourceTraceId", "firstBusinessFrameAt",
                "firstBusinessFrameLatencyMs", "parentObservationId");
        assertThatThrownBy(() -> privateValue.put("traceId", "forged"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullEmptyAndNoNamespaceKeepExistingBehavior() {
        assertThat(ObservationMetadata.remove(null)).isEmpty();
        assertThat(ObservationMetadata.with(null, null)).isEmpty();
        assertThat(ObservationMetadata.read(ObservationMetadata.with(null, context()), "t", "u"))
                .isEqualTo(context());
        assertThat(ObservationMetadata.with(Map.of(), context())).containsOnlyKeys(ObservationMetadata.KEY);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("nullable", null);
        assertThat(ObservationMetadata.remove(payload)).isSameAs(payload);
        assertThat(ObservationMetadata.with(payload, null)).isSameAs(payload);
    }

    @Test
    void copiedJsonObjectsStillRejectNullKeys() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(null, "invalid");
        payload.put(ObservationMetadata.KEY, Map.of());
        assertThatThrownBy(() -> ObservationMetadata.with(payload, context())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ObservationMetadata.remove(payload)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ObservationMetadata.with(payload, null)).isInstanceOf(NullPointerException.class);
    }

    private TurnTraceContext context() {
        return new TurnTraceContext("turn_0123456789abcdef0123456789abcdef", "t", "u", "session", "run-a",
                "root-user", null, null, "NEXT", "run-a", "root-user", 1,
                Instant.parse("2026-10-18T00:00:00Z"), null, null, false, null, true, "instance", 100L);
    }
}
