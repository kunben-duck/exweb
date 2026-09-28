/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MyBatisRouteMemoryRepositoryTest {
    @Test
    void forwardsOptionalRunExclusionWithoutAddingQueriesAndKeepsOriginalReadEntry() {
        RouteMemoryMapper mapper = mock(RouteMemoryMapper.class);
        when(mapper.findRecentRoutes("t", "u", "s", 5, "current-run")).thenReturn(List.of());
        when(mapper.findRecentRoutes("t", "u", "s", 5, null)).thenReturn(List.of());
        MyBatisRouteMemoryRepository repository = new MyBatisRouteMemoryRepository(mapper, new ObjectMapper());

        assertThat(repository.findRecentRoutes("t", "u", "s", 5, "current-run")).isEmpty();
        assertThat(repository.findRecentRoutes("t", "u", "s", 5)).isEmpty();

        verify(mapper).findRecentRoutes("t", "u", "s", 5, "current-run");
        verify(mapper).findRecentRoutes("t", "u", "s", 5, null);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "current-run")
    void mapperFiltersOnlyWhenRequestedBeforeOrderingAndLimit(String excludedRunId) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Files.newInputStream(Path.of(
                "src/main/resources/mapper/persistence/RouteMemoryMapper.opengauss.xml"))) {
            new XMLMapperBuilder(input, configuration, "route-memory", configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "tenantId", "t", "userId", "u", "sessionId", "s", "limit", 5));
        parameters.put("excludedSourceRunId", excludedRunId);
        BoundSql boundSql = configuration.getMappedStatement(RouteMemoryMapper.class.getName() + ".findRecentRoutes")
                .getBoundSql(parameters);
        String sql = boundSql.getSql();

        assertThat(sql).contains("tenant_id = ?", "user_id = ?", "session_id = ?",
                "route_source <> 'front-selected'", "ORDER BY created_at DESC", "LIMIT ?");
        if (excludedRunId == null || excludedRunId.isEmpty()) {
            assertThat(sql).doesNotContain("source_run_id IS NULL OR source_run_id <>");
            assertThat(boundSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                    .containsExactly("tenantId", "userId", "sessionId", "limit");
        } else {
            String exclusion = "AND (source_run_id IS NULL OR source_run_id <> ?)";
            assertThat(sql).contains(exclusion);
            assertThat(sql.indexOf(exclusion)).isLessThan(sql.indexOf("ORDER BY"));
            assertThat(sql.indexOf("ORDER BY")).isLessThan(sql.indexOf("LIMIT"));
            assertThat(boundSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                    .containsExactly("tenantId", "userId", "sessionId", "excludedSourceRunId", "limit");
        }
    }
}
