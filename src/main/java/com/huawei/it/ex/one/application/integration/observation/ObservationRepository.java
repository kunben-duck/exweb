/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.observation;

import com.huawei.it.ex.one.domain.observation.AgentObservationSnapshot;
import com.huawei.it.ex.one.domain.observation.TurnTraceSnapshot;

public interface ObservationRepository {
    void saveTrace(TurnTraceSnapshot snapshot);
    void saveObservation(AgentObservationSnapshot snapshot);
}
