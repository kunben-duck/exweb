/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.RouteMemoryProperties;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceHttpProperties;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceResponseMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MyBatisRouteMemoryRepositoryTest {
    @Test
    void multiIdsSurviveJsonPersistenceAndNewServiceInstance() throws Exception {
        ObjectMapper json = new ObjectMapper();
        RouteMemoryMapper mapper = mock(RouteMemoryMapper.class);
        var repository = new MyBatisRouteMemoryRepository(mapper, json);
        var writer = new RouteMemoryApplicationService(repository, (type, context) -> "rmem-1",
                new RouteMemoryProperties());
        var user = new UserContext("t", "u", "user");
        var decision = new IntentServiceResponseMapper(json, new IntentServiceHttpProperties()).toDecision(json.readTree("""
                {"data":{"result":{"routeAction":"ROUTE_MULTI","items":[
                  {"intentName":"Sales","intentId":"EX_sales"},
                  {"intentName":"Report","intentId":"report"}]}}}
                """));
        var command = new RouteMemoryApplicationService.RouteMemoryRouteCommand(user, "s", "r", "query",
                decision, RouteTarget.agentRuntime("intent-agent", 0, "multi"));
        writer.recordRouteDecision(command);
        var captured = ArgumentCaptor.forClass(RouteMemoryRow.class);
        verify(mapper).insert(captured.capture());
        RouteMemoryRow row = captured.getValue();
        assertThat(json.readTree(row.getPayloadJson()).path("candidateIntentIds"))
                .isEqualTo(json.valueToTree(List.of("EX_sales", "report")));
        assertThat(row.getIntentId()).isEqualTo("relay");
        when(mapper.findRecentRoutes("t", "u", "s", 5, null)).thenReturn(List.of(row));
        when(mapper.findActiveClarifications("t", "u", "s")).thenReturn(List.of());
        var reader = new RouteMemoryApplicationService(new MyBatisRouteMemoryRepository(mapper, json),
                (type, context) -> "unused", new RouteMemoryProperties());
        assertThat(reader.loadForIntent(user, "s", "user_correction", Map.of()).history())
                .containsExactly(writer.routeHistory(command));
        assertThat(writer.routeHistory(command)).containsEntry("intentId", "EX_sales;report");
        var oldPayload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(row.getPayloadJson());
        oldPayload.remove("candidateIntentIds");
        row.setPayloadJson(json.writeValueAsString(oldPayload));
        assertThat(reader.loadForIntent(user, "s", "user_correction", Map.of()).history()).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("intent", "Sales;Report").doesNotContainKey("intentId"));
    }

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
