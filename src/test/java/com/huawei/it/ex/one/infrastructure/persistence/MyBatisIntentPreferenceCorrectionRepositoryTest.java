/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.domain.intent.IntentPreferenceCorrection;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                "INTENT_CANDIDATE", "原问题", "偏好意图", "EX_preference", "原始意图", CREATED_AT, UPDATED_AT));
        List<IntentUserPreferenceCorrection> recent = repository.findRecent(
                "tenant", "user", "entry", 5);

        assertThat(written.get()).isEqualTo(new IntentPreferenceCorrectionWriteRow(
                "pref-1", "tenant", "user", "entry", "session", "msg-1",
                "INTENT_CANDIDATE", "原问题", "偏好意图", "EX_preference", "原始意图", CREATED_AT, UPDATED_AT));
        assertThat(requestedLimit.get()).isEqualTo(5);
        assertThat(recent).containsExactly(new IntentUserPreferenceCorrection(
                "原问题", "偏好意图", "EX_preference", "原始意图", UPDATED_AT));
    }

    @Test
    void zeroLimitSkipsMapper() {
        AtomicInteger requestedLimit = new AtomicInteger(-1);
        MyBatisIntentPreferenceCorrectionRepository repository =
                new MyBatisIntentPreferenceCorrectionRepository(mapper(new AtomicReference<>(), requestedLimit));

        assertThat(repository.findRecent("tenant", "user", "entry", 0)).isEmpty();
        assertThat(requestedLimit.get()).isEqualTo(-1);
    }

    @Test
    void legacyConstructionAndNullIdKeepTheOldProjection() {
        var legacy = new IntentPreferenceCorrection("pref", "t", "u", "entry", "s", "q",
                "INTENT_CANDIDATE", "query", "name", null, CREATED_AT, UPDATED_AT);
        AtomicReference<IntentPreferenceCorrectionWriteRow> written = new AtomicReference<>();
        var repository = new MyBatisIntentPreferenceCorrectionRepository(mapper(written, new AtomicInteger()));
        repository.upsert(legacy);
        assertThat(written.get().preferenceIntentId()).isNull();
        assertThat(new IntentPreferenceCorrectionReadRow("query", "name", null, UPDATED_AT).preferenceIntentId())
                .isNull();
        assertThat(new IntentUserPreferenceCorrection("query", "name", null, UPDATED_AT).preferenceIntentId())
                .isNull();
    }

    @Test
    void mapperUpdatesIdentityEvenWhenNullAndDdlMatches() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Files.newInputStream(Path.of(
                "src/main/resources/mapper/persistence/IntentPreferenceCorrectionMapper.opengauss.xml"))) {
            new XMLMapperBuilder(input, configuration, "preference", configuration.getSqlFragments()).parse();
        }
        String namespace = IntentPreferenceCorrectionMapper.class.getName() + ".";
        var write = configuration.getMappedStatement(namespace + "upsert").getBoundSql(Map.of());
        assertThat(write.getSql()).contains("preference_intent_id", "preference_intent_id = EXCLUDED.preference_intent_id")
                .doesNotContain("COALESCE");
        assertThat(write.getParameterMappings()).hasSize(13)
                .extracting(mapping -> mapping.getProperty()).contains("preferenceIntentId");
        var read = configuration.getMappedStatement(namespace + "findRecent").getBoundSql(Map.of());
        assertThat(read.getSql()).contains("preference_intent_id AS preferenceIntentId", "tenant_id = ?",
                "user_id = ?", "intent_access_name = ?", "ORDER BY updated_at DESC, id DESC", "LIMIT ?");
        assertThat(read.getParameterMappings()).hasSize(4);
        for (String file : List.of("init-20260718.sql", "incremental-20260827-intent-preference-corrections.sql",
                "incremental-20261114-intent-preference-id.sql")) {
            String ddl = Files.readString(Path.of("src/main/resources/db", file));
            assertThat(ddl).contains("preference_intent_id NVARCHAR2(128)",
                    "COMMENT ON COLUMN fin_ex_intent_preference_correction_t.preference_intent_id IS");
            assertThat(ddl).doesNotContain("preference_intent_id NVARCHAR2(128) NOT NULL",
                    "preference_intent_id NVARCHAR2(128) DEFAULT");
        }
        String upgrade = Files.readString(Path.of("src/main/resources/db/incremental-20261114-intent-preference-id.sql"));
        assertThat(upgrade).contains("BEGIN;", "ADD COLUMN preference_intent_id", "COMMIT;")
                .doesNotContain("DROP ", "TRUNCATE ", "UPDATE ", "CREATE INDEX");
    }

    @Test
    void actualResultMappingSelectsNewConstructorAndAcceptsLegacyNullId() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Files.newInputStream(Path.of(
                "src/main/resources/mapper/persistence/IntentPreferenceCorrectionMapper.opengauss.xml"))) {
            new XMLMapperBuilder(input, configuration, "projection", configuration.getSqlFragments()).parse();
        }
        var mapped = configuration.getMappedStatement(IntentPreferenceCorrectionMapper.class.getName() + ".findRecent");
        for (String id : new String[] {null, "EX_new"}) {
            var result = mock(java.sql.ResultSet.class);
            var metadata = mock(java.sql.ResultSetMetaData.class);
            var statement = mock(java.sql.Statement.class);
            when(statement.getResultSet()).thenReturn(result);
            when(result.getMetaData()).thenReturn(metadata);
            when(result.next()).thenReturn(true, false);
            when(metadata.getColumnCount()).thenReturn(5);
            String[] columns = {"queryText", "preferenceIntent", "preferenceIntentId", "originalIntent", "updatedAt"};
            for (int index = 0; index < columns.length; index++) {
                when(metadata.getColumnLabel(index + 1)).thenReturn(columns[index]);
                when(metadata.getColumnType(index + 1)).thenReturn(index == 4 ? java.sql.Types.TIMESTAMP : java.sql.Types.VARCHAR);
                when(metadata.getColumnClassName(index + 1)).thenReturn(index == 4 ? "java.sql.Timestamp" : "java.lang.String");
            }
            when(result.getString("queryText")).thenReturn("query");
            when(result.getString("preferenceIntent")).thenReturn("name");
            when(result.getString("preferenceIntentId")).thenReturn(id);
            when(result.getTimestamp("updatedAt")).thenReturn(java.sql.Timestamp.from(UPDATED_AT));
            var handler = new org.apache.ibatis.executor.resultset.DefaultResultSetHandler(
                    mock(org.apache.ibatis.executor.Executor.class), mapped, null, null,
                    mapped.getBoundSql(Map.of()), org.apache.ibatis.session.RowBounds.DEFAULT);
            assertThat(handler.handleResultSets(statement)).containsExactly(
                    new IntentPreferenceCorrectionReadRow("query", "name", id, null, UPDATED_AT));
        }
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
                                "原问题", "偏好意图", "EX_preference", "原始意图", UPDATED_AT));
                    }
                    default -> null;
                });
    }
}
