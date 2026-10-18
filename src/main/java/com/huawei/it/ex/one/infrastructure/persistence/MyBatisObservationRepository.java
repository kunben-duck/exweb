/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.observation.ObservationRepository;
import com.huawei.it.ex.one.domain.observation.AgentObservationSnapshot;
import com.huawei.it.ex.one.domain.observation.ObservationMetadata;
import com.huawei.it.ex.one.domain.observation.TurnTraceSnapshot;

import org.springframework.stereotype.Repository;

@Repository
public class MyBatisObservationRepository implements ObservationRepository {
    private final ObservationMapper mapper;
    private final ChatRunRepository runs;

    public MyBatisObservationRepository(ObservationMapper mapper, ChatRunRepository runs) {
        this.mapper = mapper;
        this.runs = runs;
    }

    @Override
    public void saveTrace(TurnTraceSnapshot snapshot) {
        // 仅派生问答需要补来源；在独立观测事务内读取可信Run，不阻塞业务准入，也不读观测表恢复。
        if (snapshot.sourceRunId() != null && snapshot.sourceTraceId() == null
                && !snapshot.sourceRunId().equals(snapshot.currentRunId())) {
            String tenantId = snapshot.tenantId();
            String userId = snapshot.userId();
            var source = runs.findById(snapshot.sourceRunId())
                    .filter(run -> tenantId.equals(run.tenantId()) && userId.equals(run.userId()));
            if (source.isPresent()) {
                var context = ObservationMetadata.read(source.get().metadata(), snapshot.tenantId(), snapshot.userId());
                if (context != null) { snapshot = snapshot.withSourceTrace(context.traceId()); }
            }
        }
        mapper.saveTrace(snapshot);
    }
    @Override public void saveObservation(AgentObservationSnapshot snapshot) { mapper.saveObservation(snapshot); }
}
