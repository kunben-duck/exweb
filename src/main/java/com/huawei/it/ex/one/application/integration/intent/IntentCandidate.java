/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

/** Intent置信度接口返回的单个候选技能。 */
public record IntentCandidate(
        String intentId,
        String accessName,
        String skillId,
        String intentName,
        Double confidence
) {
}
