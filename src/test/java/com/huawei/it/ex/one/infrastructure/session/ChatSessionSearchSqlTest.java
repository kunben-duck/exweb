/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

class ChatSessionSearchSqlTest {
    @Test
    void cursorAndNumberPagesUseIdenticalOwnerScopedKeywordPredicates() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = parameters();
        BoundSql bound = statement(configuration, "findPageByOwner", parameters);
        String cursor = sql(bound);
        String count = sql(statement(configuration, "countPageByOwner", parameters));
        String page = sql(statement(configuration, "findNumberPageByOwner", parameters));

        String predicate = keywordPredicate(cursor);
        assertThat(predicate).isEqualTo(keywordPredicate(count)).isEqualTo(keywordPredicate(page));
        assertThat(predicate).contains("title ILIKE ? ESCAPE '!'", "OR EXISTS ( SELECT 1",
                "m.tenant_id = ?", "m.user_id = ?", "m.session_id = fin_ex_chat_session_t.id",
                "m.role IN ('user', 'assistant')", "m.content ILIKE ? ESCAPE '!'");
        assertThat(predicate).doesNotContain("current_leaf", "parent_message", "metadata", "attachment", "part");
        assertThat(cursor).contains("WHERE tenant_id = ? AND user_id = ? AND status <> 'DELETED'",
                "AND app_id = ?", "AND channel = ?",
                "AND ( updated_at < ? OR (updated_at = ? AND id < ?) ) ORDER BY updated_at DESC, id DESC LIMIT ?");
        assertThat(cursor.indexOf(predicate)).isLessThan(cursor.indexOf("updated_at <"));
        assertThat(cursor).doesNotContain("COUNT(", " JOIN ", "OFFSET", "%profit%");
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly(
                "tenantId", "userId", "appId", "keywordPattern", "tenantId", "userId", "keywordPattern",
                "channel", "cursorUpdatedAt", "cursorUpdatedAt", "cursorId", "limit");
    }

    @Test
    void ordinaryAndTitleOnlyQueriesDoNotAccessMessagesAndMainSiteRemainsIsolated() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = parameters();
        parameters.put("keywordPattern", null);
        String ordinary = sql(statement(configuration, "findPageByOwner", parameters));
        assertThat(ordinary).doesNotContain("ILIKE", "EXISTS", "fin_ex_chat_message_t");
        parameters.put("titlePattern", "%title%");
        String title = sql(statement(configuration, "findPageByOwner", parameters));
        assertThat(title).contains("AND title ILIKE ? ESCAPE '!'").doesNotContain("fin_ex_chat_message_t");
        parameters.put("titlePattern", null);
        parameters.put("keywordPattern", "%profit%");
        parameters.put("mainSiteOnly", true);
        parameters.put("appId", null);
        parameters.put("cursorUpdatedAt", null);
        String mainSite = sql(statement(configuration, "findPageByOwner", parameters));
        assertThat(mainSite).contains("AND app_id IS NULL", "m.content ILIKE ?")
                .doesNotContain("AND app_id = ?", "updated_at < ?");
    }

    private Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/session/ChatSessionMapper.opengauss.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private Map<String, Object> parameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", "tenant1");
        parameters.put("userId", "user1");
        parameters.put("appId", "app");
        parameters.put("titlePattern", null);
        parameters.put("keywordPattern", "%profit%");
        parameters.put("channel", "mobile");
        parameters.put("mainSiteOnly", false);
        parameters.put("cursorUpdatedAt", Instant.EPOCH);
        parameters.put("cursorId", "session2");
        parameters.put("limit", 21);
        parameters.put("offset", 0);
        return parameters;
    }

    private BoundSql statement(Configuration configuration, String id, Map<String, Object> parameters) {
        return configuration.getMappedStatement(ChatSessionMapper.class.getName() + "." + id).getBoundSql(parameters);
    }

    private String sql(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").trim();
    }

    private String keywordPredicate(String sql) {
        return sql.substring(sql.indexOf("AND ( title"), sql.indexOf("AND channel"));
    }
}
