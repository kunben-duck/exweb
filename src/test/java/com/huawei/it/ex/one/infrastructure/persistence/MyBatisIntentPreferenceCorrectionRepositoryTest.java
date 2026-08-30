/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class MyBatisIntentPreferenceCorrectionRepositoryTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-27T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-27T02:00:00Z");

    @Test
    void mapsUpsertAndNewestPreferenceProjection() {
        AtomicReference<IntentPreferenceCorrectionWriteRow> written = new AtomicReference<>();
        AtomicInteger requestedLimit = new AtomicInteger();
        IntentPreferenceCorrectionMapper mapper = mapper(written, requestedLimit);
        MyBatisIntentPreferenceCorrectionRepository repository =
                new MyBatisIntentPreferenceCorrectionRepository(mapper);

        repository.upsert(new IntentPreferenceCorrection(
                "pref-1", "tenant", "user", "entry", "session", "msg-1",
                "INTENT_CANDIDATE", "原问题", "偏好意图", "原始意图", CREATED_AT, UPDATED_AT));
        List<IntentUserPreferenceCorrection> recent = repository.findRecent(
                "tenant", "user", "entry", 5);

        assertThat(written.get()).isEqualTo(new IntentPreferenceCorrectionWriteRow(
                "pref-1", "tenant", "user", "entry", "session", "msg-1",
                "INTENT_CANDIDATE", "原问题", "偏好意图", "原始意图", CREATED_AT, UPDATED_AT));
        assertThat(requestedLimit.get()).isEqualTo(5);
        assertThat(recent).containsExactly(new IntentUserPreferenceCorrection(
                "原问题", "偏好意图", "原始意图", UPDATED_AT));
    }

    @Test
    void zeroLimitSkipsMapper() {
        AtomicInteger requestedLimit = new AtomicInteger(-1);
        MyBatisIntentPreferenceCorrectionRepository repository =
                new MyBatisIntentPreferenceCorrectionRepository(mapper(new AtomicReference<>(), requestedLimit));

        assertThat(repository.findRecent("tenant", "user", "entry", 0)).isEmpty();
        assertThat(requestedLimit.get()).isEqualTo(-1);
    }

    private IntentPreferenceCorrectionMapper mapper(
            AtomicReference<IntentPreferenceCorrectionWriteRow> written,
            AtomicInteger requestedLimit) {
        return (IntentPreferenceCorrectionMapper) Proxy.newProxyInstance(
                IntentPreferenceCorrectionMapper.class.getClassLoader(),
                new Class<?>[] {IntentPreferenceCorrectionMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "upsert" -> {
                        written.set((IntentPreferenceCorrectionWriteRow) args[0]);
                        yield null;
                    }
                    case "findRecent" -> {
                        requestedLimit.set((Integer) args[3]);
                        yield List.of(new IntentPreferenceCorrectionReadRow(
                                "原问题", "偏好意图", "原始意图", UPDATED_AT));
                    }
                    default -> null;
                });
    }
}
