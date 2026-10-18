/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.observation;

/** 单次实际调用尝试的不可变关联信息。 */
public record AgentObservationContext(
        TurnTraceContext turn, String observationId, String parentObservationId, String retryOfObservationId,
        int attemptNo, String agentType, String agentMode, String operation, String skillId, String runtimeSessionId
) { }
