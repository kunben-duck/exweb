/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.service.observation.AgentObservationRecorder;
import com.huawei.it.ex.one.application.service.observation.ObservationTestFixture;

import reactor.core.publisher.SignalType;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class ObservationPersistenceTest {
    @Test
    void bothUpsertsBindEveryFieldAndKeepIndependentMonotonicMergeGuards() throws Exception {
        Configuration config = new Configuration();
        String path = "mapper/persistence/ObservationMapper.opengauss.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            ObservationTestFixture.await(() -> f.traces.size() == 2);
            var call = f.recorder.begin(f.turns.context("run-a"),
                    new AgentObservationRecorder.Target("RELAY", "DOMAIN_EXPERT", "QUERY", "finance", "relay-real"));
            call.finish(SignalType.ON_COMPLETE);
            ObservationTestFixture.await(() -> f.calls.size() == 2);
            var trace = config.getMappedStatement(ObservationMapper.class.getName() + ".saveTrace")
                    .getBoundSql(f.traces.getLast());
            assertThat(trace.getParameterMappings()).hasSize(22);
            String sql = trace.getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("EXCLUDED.current_run_ordinal > fin_ex_turn_trace_t.current_run_ordinal",
                    "EXCLUDED.current_run_id = fin_ex_turn_trace_t.current_run_id",
                    "EXCLUDED.state_version > fin_ex_turn_trace_t.state_version",
                    "is_refusal = fin_ex_turn_trace_t.is_refusal OR EXCLUDED.is_refusal",
                    "EXCLUDED.first_business_frame_at < fin_ex_turn_trace_t.first_business_frame_at");
            var observation = config.getMappedStatement(ObservationMapper.class.getName() + ".saveObservation")
                    .getBoundSql(f.calls.getLast());
            assertThat(observation.getParameterMappings()).hasSize(28);
            assertThat(observation.getSql()).contains(
                    "fin_ex_agent_observation_t.status != 'FINISHED' AND EXCLUDED.status = 'FINISHED'")
                    .doesNotContain("error_code", "error_message", "error_type");
            for (var parameter : observation.getParameterMappings()) {
                assertThat(config.newMetaObject(f.calls.getLast()).hasGetter(parameter.getProperty())).isTrue();
            }
        }
    }

    @Test
    void derivedTraceResolvesOnlyTrustedSourceAndOrdinaryTraceAddsNoSourceRead() {
        try (var f = new ObservationTestFixture()) {
            var original = f.start("run-original");
            ObservationTestFixture.await(() -> f.traces.size() == 2);
            var mapper = mock(ObservationMapper.class);
            var runs = mock(ChatRunRepository.class);
            var repository = new MyBatisObservationRepository(mapper, runs);
            repository.saveTrace(f.traces.getLast());
            verifyNoInteractions(runs);
            f.start("run-new");
            f.turns.source("run-new", original.id(), null, "CANDIDATE_SWITCH");
            f.turns.committed(f.turns.decorate(f.run("run-new", "user-1", java.util.Map.of()).completed(3)), null);
            ObservationTestFixture.await(() -> f.traces.size() == 5);
            var derived = f.traces.getLast();
            when(runs.findById(original.id())).thenReturn(Optional.of(original));
            repository.saveTrace(derived);
            verify(mapper).saveTrace(derived.withSourceTrace(f.turns.context(original.id()).traceId()));
            var foreign = mock(com.huawei.it.ex.one.domain.chat.ChatRun.class);
            when(foreign.tenantId()).thenReturn("other-tenant");
            when(runs.findById(original.id())).thenReturn(Optional.of(foreign));
            repository.saveTrace(derived);
            verify(mapper).saveTrace(derived);
        }
    }
}
