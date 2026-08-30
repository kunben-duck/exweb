/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceCorrectionRepository;
import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;

import org.springframework.stereotype.Repository;

import java.util.List;

/** openGauss persistence adapter for cross-session Intent preference corrections. */
@Repository
public class MyBatisIntentPreferenceCorrectionRepository implements IntentPreferenceCorrectionRepository {
    private final IntentPreferenceCorrectionMapper mapper;

    public MyBatisIntentPreferenceCorrectionRepository(IntentPreferenceCorrectionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void upsert(IntentPreferenceCorrection correction) {
        mapper.upsert(new IntentPreferenceCorrectionWriteRow(
                correction.id(), correction.tenantId(), correction.userId(), correction.intentAccessName(),
                correction.sessionId(), correction.sourceMessageId(), correction.sourceType(),
                correction.queryText(), correction.preferenceIntent(), correction.originalIntent(),
                correction.createdAt(), correction.updatedAt()));
    }

    @Override
    public List<IntentUserPreferenceCorrection> findRecent(
            String tenantId, String userId, String intentAccessName, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return mapper.findRecent(tenantId, userId, intentAccessName, limit).stream()
                .map(row -> new IntentUserPreferenceCorrection(
                        row.queryText(), row.preferenceIntent(), row.originalIntent(), row.updatedAt()))
                .toList();
    }
}
