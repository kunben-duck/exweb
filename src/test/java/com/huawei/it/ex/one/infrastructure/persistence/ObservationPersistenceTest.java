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
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

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
            assertAuditColumnBindings(trace);
            String sql = trace.getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("EXCLUDED.current_run_ordinal > fin_ex_turn_trace_t.current_run_ordinal",
                    "EXCLUDED.current_run_id = fin_ex_turn_trace_t.current_run_id",
                    "EXCLUDED.state_version > fin_ex_turn_trace_t.state_version",
                    "is_refusal = fin_ex_turn_trace_t.is_refusal OR EXCLUDED.is_refusal",
                    "EXCLUDED.first_business_frame_at < fin_ex_turn_trace_t.first_business_frame_at",
                    "last_update_date = CASE WHEN EXCLUDED.last_update_date > fin_ex_turn_trace_t.last_update_date "
                            + "THEN EXCLUDED.last_update_date ELSE fin_ex_turn_trace_t.last_update_date END");
            var observation = config.getMappedStatement(ObservationMapper.class.getName() + ".saveObservation")
                    .getBoundSql(f.calls.getLast());
            assertThat(observation.getParameterMappings()).hasSize(28);
            assertAuditColumnBindings(observation);
            assertThat(observation.getSql()).contains(
                    "fin_ex_agent_observation_t.status != 'FINISHED' AND EXCLUDED.status = 'FINISHED'")
                    .doesNotContain("error_code", "error_message", "error_type");
            assertThat(observation.getSql().replaceAll("\\s+", " ")).contains(
                    "last_update_date = CASE WHEN fin_ex_agent_observation_t.status != 'FINISHED' "
                            + "AND EXCLUDED.status = 'FINISHED' THEN EXCLUDED.last_update_date "
                            + "ELSE fin_ex_agent_observation_t.last_update_date END");
            for (var parameter : observation.getParameterMappings()) {
                assertThat(config.newMetaObject(f.calls.getLast()).hasGetter(parameter.getProperty())).isTrue();
            }
        }
    }

    @Test
    void bothSchemasKeepMatchingAuditColumnsAndTableNamesWithinThirtyCharacters() throws Exception {
        String init = Files.readString(Path.of("src/main/resources/db/init-20260718.sql"));
        String incremental = Files.readString(Path.of("src/main/resources/db/incremental-20261018-turn-observation.sql"));
        for (String table : List.of("fin_ex_turn_trace_t", "fin_ex_agent_observation_t")) {
            assertThat(table.length()).isLessThanOrEqualTo(30);
            assertThat(tableDefinition(init, table)).isEqualTo(tableDefinition(incremental, table))
                    .contains("creation_date TIMESTAMPTZ NOT NULL", "last_update_date TIMESTAMPTZ NOT NULL",
                            "started_at TIMESTAMPTZ NOT NULL", "first_business_frame_at TIMESTAMPTZ",
                            "finished_at TIMESTAMPTZ")
                    .doesNotContain("created_at", "updated_at");
            for (String schema : List.of(init, incremental)) {
                assertThat(schema).contains("COMMENT ON COLUMN " + table + ".creation_date IS",
                                "COMMENT ON COLUMN " + table + ".last_update_date IS")
                        .doesNotContain("COMMENT ON COLUMN " + table + ".created_at IS",
                                "COMMENT ON COLUMN " + table + ".updated_at IS");
            }
        }
        assertThat(init.lastIndexOf("CREATE TABLE ")).isLessThan(init.indexOf("COMMENT ON "));
        assertThat(init.lastIndexOf("CREATE INDEX ")).isLessThan(init.indexOf("COMMENT ON "));
        assertThat(init.lastIndexOf("CREATE SEQUENCE ")).isLessThan(init.indexOf("COMMENT ON "));
    }

    @Test
    void deployedSchemaUpgradeOnlyRenamesAuditColumnsAndConvertsDeclaredStringsInOneTransaction() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/init-20260718.sql"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/incremental-20261027-observation-column-standardization.sql"));
        List<String> tables = List.of("fin_ex_turn_trace_t", "fin_ex_agent_observation_t");
        List<String> expected = new ArrayList<>();
        expected.add("START TRANSACTION;");
        for (String table : tables) {
            expected.add("ALTER TABLE " + table + " RENAME COLUMN created_at TO creation_date;");
            expected.add("ALTER TABLE " + table + " RENAME COLUMN updated_at TO last_update_date;");
        }
        Pattern stringColumn = Pattern.compile("(?m)^\\s+(\\w+) (NVARCHAR2\\(\\d+\\))");
        for (int i = 0; i < tables.size(); i++) {
            String table = tables.get(i);
            List<String> alterations = stringColumn.matcher(tableDefinition(schema, table)).results()
                    .map(match -> "ALTER COLUMN " + match.group(1) + " TYPE " + match.group(2))
                    .toList();
            assertThat(alterations).hasSize(i == 0 ? 12 : 18);
            expected.add("ALTER TABLE " + table + " " + String.join(", ", alterations) + ";");
        }
        for (String table : tables) {
            List<String> comments = schema.lines().filter(line ->
                    line.startsWith("COMMENT ON COLUMN " + table + ".creation_date IS ")
                            || line.startsWith("COMMENT ON COLUMN " + table + ".last_update_date IS ")).toList();
            assertThat(comments).hasSize(2);
            expected.addAll(comments);
        }
        expected.add("COMMIT;");
        String statements = migration.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining(" ")).replaceAll("\\s+", " ").trim();
        assertThat(statements).isEqualTo(String.join(" ", expected));
    }

    private static void assertAuditColumnBindings(BoundSql statement) {
        String sql = statement.getSql().replaceAll("\\s+", " ");
        assertThat(sql).contains(", creation_date, last_update_date ) VALUES (")
                .doesNotContain("created_at", "updated_at");
        assertThat(sql.substring(sql.indexOf("ON DUPLICATE KEY UPDATE"))).doesNotContain("creation_date");
        assertThat(statement.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .endsWith("createdAt", "updatedAt");
    }

    private static String tableDefinition(String schema, String table) {
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = schema.indexOf(");", start);
        assertThat(end).isGreaterThan(start);
        return schema.substring(start, end + 2);
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
