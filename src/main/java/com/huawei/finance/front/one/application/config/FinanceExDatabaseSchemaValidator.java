package com.huawei.finance.front.one.application.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 校验聊天并发准入依赖的数据库索引，避免首次建库脚本遗漏后带病启动。
 */
@Component
public class FinanceExDatabaseSchemaValidator implements SmartInitializingSingleton {
    static final String ACTIVE_RUN_TABLE = "fin_ex_chat_run_t";
    static final String ACTIVE_RUN_INDEX = "uk_fin_ex_chat_run_active_session";

    private static final Logger log = LoggerFactory.getLogger(FinanceExDatabaseSchemaValidator.class);
    private static final Pattern ACTIVE_RUN_COLUMNS = Pattern.compile(
            "\\(\\s*tenant_id\\s*,\\s*user_id\\s*,\\s*session_id\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']*)'");
    private static final String INDEX_QUERY = """
            SELECT i.indisunique AS is_unique,
                   i.indisvalid AS is_valid,
                   pg_get_indexdef(i.indexrelid) AS index_definition
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class table_rel ON table_rel.oid = i.indrelid
            JOIN pg_catalog.pg_class index_rel ON index_rel.oid = i.indexrelid
            JOIN pg_catalog.pg_namespace namespace_rel ON namespace_rel.oid = table_rel.relnamespace
            WHERE namespace_rel.nspname = current_schema()
              AND table_rel.relname = ?
              AND index_rel.relname = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public FinanceExDatabaseSchemaValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(INDEX_QUERY, ACTIVE_RUN_TABLE, ACTIVE_RUN_INDEX);
        } catch (RuntimeException ex) {
            throw invalidSchema("无法读取 active-run 唯一索引定义", ex);
        }
        validateActiveRunIndex(rows);
        log.info("FinanceEX database schema validation passed: {}", ACTIVE_RUN_INDEX);
    }

    static void validateActiveRunIndex(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() != 1) {
            throw invalidSchema("缺少 active-run 唯一索引 " + ACTIVE_RUN_INDEX, null);
        }
        Map<String, Object> row = rows.get(0);
        if (!booleanValue(row.get("is_unique"))) {
            throw invalidSchema("索引 " + ACTIVE_RUN_INDEX + " 不是 UNIQUE 索引", null);
        }
        if (!booleanValue(row.get("is_valid"))) {
            throw invalidSchema("索引 " + ACTIVE_RUN_INDEX + " 当前无效", null);
        }
        String definition = String.valueOf(row.getOrDefault("index_definition", ""))
                .replace("\"", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        boolean validColumns = ACTIVE_RUN_COLUMNS.matcher(definition).find();
        int whereIndex = definition.indexOf("where");
        String predicate = whereIndex < 0 ? "" : definition.substring(whereIndex);
        Set<String> statusValues = QUOTED_LITERAL.matcher(predicate).results()
                .map(result -> result.group(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        boolean membershipPredicate = predicate.contains(" in ") || predicate.contains("= any");
        boolean validPredicate = predicate.contains("status")
                && membershipPredicate
                && !predicate.contains(" not ")
                && !predicate.contains(" and ")
                && !predicate.contains(" or ")
                && statusValues.equals(Set.of("running", "cancelling"));
        if (!validColumns || !validPredicate) {
            throw invalidSchema("索引 " + ACTIVE_RUN_INDEX + " 的列或状态条件不符合首次建库要求", null);
        }
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static IllegalStateException invalidSchema(String detail, RuntimeException cause) {
        String message = "FinanceEX 首次建库 schema 未完整执行: " + detail
                + "。请使用 src/main/resources/db/schema.sql 完成建库后再启动";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
