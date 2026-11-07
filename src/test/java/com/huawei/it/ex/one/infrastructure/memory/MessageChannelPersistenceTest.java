/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class MessageChannelPersistenceTest {
    @Test
    void insertAndHistoryQueriesCarryChannelButUpdatesNeverOverwriteIt() throws Exception {
        Configuration config = new Configuration();
        String path = "mapper/memory/ChatMessageMapper.opengauss.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
        String namespace = ChatMessageMapper.class.getName() + ".";
        ChatMessageRow row = new ChatMessageRow();
        row.setChannel("mobile");
        var insert = config.getMappedStatement(namespace + "insert").getBoundSql(row);
        assertThat(insert.getParameterMappings()).extracting(ParameterMapping::getProperty).contains("channel");
        assertThat(config.getResultMap(namespace + "chatMessageResultMap").getResultMappings())
                .anySatisfy(mapping -> {
                    assertThat(mapping.getColumn()).isEqualTo("channel");
                    assertThat(mapping.getProperty()).isEqualTo("channel");
                });
        for (String update : List.of("updateAssistant", "updateAssistantMetadata")) {
            assertThat(config.getMappedStatement(namespace + update).getBoundSql(row).getSql())
                    .doesNotContain("channel");
        }
        Map<String, Object> params = Map.of("tenantId", "tenant", "userId", "user", "sessionId", "session",
                "messageIds", List.of("msg"), "sessionIds", List.of("session"), "limit", 50);
        for (String query : List.of("findRecentActivePath", "findActivePath", "findActivePathPage",
                "findAllBySession", "findByOwnerAndId", "findByOwnerSessionAndIds", "findSiblings",
                "findFirstAssistantBySessions")) {
            assertThat(config.getMappedStatement(namespace + query).getBoundSql(params).getSql()).contains("channel");
        }
    }

    @Test
    void migrationOnlyAddsNullableColumnWithoutBackfillAndFailsFastOnBusyTable() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/incremental-20261107-message-channel.sql"))
                .replaceAll("(?m)^--.*$", "").replaceAll("\\s+", " ").trim();
        assertThat(migration).startsWith("BEGIN; LOCK TABLE fin_ex_chat_message_t IN ACCESS EXCLUSIVE MODE NOWAIT;")
                .contains("ALTER TABLE fin_ex_chat_message_t ADD COLUMN channel NVARCHAR2(64);")
                .endsWith("COMMIT;")
                .doesNotContain("UPDATE ", "DEFAULT", "NOT NULL", "CREATE INDEX", "DROP ");
        String init = Files.readString(Path.of("src/main/resources/db/init-20260718.sql"));
        String table = init.substring(init.indexOf("CREATE TABLE IF NOT EXISTS fin_ex_chat_message_t ("));
        assertThat(table.substring(0, table.indexOf(");"))).contains("channel NVARCHAR2(64)\n");
        assertThat(init.lastIndexOf("CREATE TABLE ")).isLessThan(init.indexOf("COMMENT ON "));
    }
}
