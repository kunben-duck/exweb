/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageInput;
import com.huawei.it.ex.one.infrastructure.memory.ChatMessageMapper;
import com.huawei.it.ex.one.infrastructure.memory.MyBatisChatMessageStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class IntentFeedbackPersistenceTest {
    @Test
    void productionMessageStoreOnlyCallsInputMapper() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageInput input = new ChatMessageInput("s", "user", "query");
        when(mapper.findInputByOwnerAndId("t", "u", "q")).thenReturn(Optional.of(input));
        MyBatisChatMessageStore store = new MyBatisChatMessageStore(
                mapper, new ObjectMapper(), new ChatStreamProperties());
        assertThat(store.findInputByOwnerAndId("t", "u", "q")).contains(input);
        verify(mapper).findInputByOwnerAndId("t", "u", "q");
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test
    void mapperUsesStrictInsertAndOwnerScopedReadWithSafeEmptyBatch() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Files.newInputStream(Path.of(
                "src/main/resources/mapper/persistence/IntentFeedbackMapper.opengauss.xml"))) {
            new XMLMapperBuilder(input, configuration, "feedback", configuration.getSqlFragments()).parse();
        }
        String prefix = IntentFeedbackMapper.class.getName() + ".";
        BoundSql read = configuration.getMappedStatement(prefix + "findByOwnerAndRuns").getBoundSql(Map.of(
                "tenantId", "t", "userId", "u", "runIds", List.of("a", "b")));
        assertThat(read.getSql()).contains("tenant_id = ?", "user_id = ?", "run_id IN").doesNotContain("FOR UPDATE");
        assertThat(read.getParameterMappings()).hasSize(4);
        assertThat(configuration.getMappedStatement(prefix + "findByOwnerAndRuns").getBoundSql(
                Map.of("tenantId", "t", "userId", "u", "runIds", List.of())).getSql()).contains("AND 1 = 0");
        assertThat(configuration.getMappedStatement(prefix + "insert").getBoundSql(Map.of()).getSql())
                .contains("INSERT INTO fin_ex_intent_feedback_t").doesNotContain("UPDATE", "ON DUPLICATE");
        String ddl = Files.readString(Path.of("src/main/resources/db/incremental-20260908-intent-feedback.sql"));
        assertThat(ddl).contains("UNIQUE (tenant_id, user_id, run_id)").doesNotContain("FOREIGN KEY");
    }

    @Test
    void emptyRepositoryBatchSkipsMapper() {
        IntentFeedbackMapper mapper = mock(IntentFeedbackMapper.class);
        assertThat(new MyBatisIntentFeedbackRepository(mapper).findByOwnerAndRuns("t", "u", List.of())).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void lightweightInputStatementDoesNotReadMetadataPartsOrAttachments() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = Files.newInputStream(Path.of(
                "src/main/resources/mapper/memory/ChatMessageMapper.opengauss.xml"))) {
            new XMLMapperBuilder(input, configuration, "message", configuration.getSqlFragments()).parse();
        }
        String sql = configuration.getMappedStatement(ChatMessageMapper.class.getName() + ".findInputByOwnerAndId")
                .getBoundSql(Map.of("tenantId", "t", "userId", "u", "messageId", "q")).getSql();
        assertThat(sql).contains("session_id AS sessionId, role, content", "tenant_id = ?", "user_id = ?", "id = ?")
                .doesNotContain("metadata", "part", "attachment");
    }
}
