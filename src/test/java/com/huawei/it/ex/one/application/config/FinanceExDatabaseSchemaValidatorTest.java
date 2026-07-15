package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FinanceExDatabaseSchemaValidatorTest {
    @Test
    void validActiveRunIndexPassesStartupValidation() {
        List<Map<String, Object>> rows = List.of(Map.of(
                "is_unique", true,
                "is_valid", true,
                "index_definition", "CREATE UNIQUE INDEX uk_fin_ex_chat_run_active_session "
                        + "ON public.fin_ex_chat_run_t USING btree (tenant_id, user_id, session_id) "
                        + "WHERE status IN ('RUNNING', 'CANCELLING')"
        ));

        assertThatCode(() -> validator(rows).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    @Test
    void missingActiveRunIndexStopsStartup() {
        assertThatThrownBy(() -> validator(List.of()).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("首次建库 schema 未完整执行")
                .hasMessageContaining(FinanceExDatabaseSchemaValidator.ACTIVE_RUN_INDEX);
    }

    @Test
    void malformedActiveRunIndexStopsStartup() {
        List<Map<String, Object>> rows = List.of(Map.of(
                "is_unique", false,
                "is_valid", true,
                "index_definition", "CREATE INDEX uk_fin_ex_chat_run_active_session "
                        + "ON public.fin_ex_chat_run_t (tenant_id, user_id, session_id)"
        ));

        assertThatThrownBy(() -> validator(rows).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是 UNIQUE 索引");
    }

    @Test
    void activeRunIndexWithWrongPredicateStopsStartup() {
        List<Map<String, Object>> rows = List.of(Map.of(
                "is_unique", true,
                "is_valid", true,
                "index_definition", "CREATE UNIQUE INDEX uk_fin_ex_chat_run_active_session "
                        + "ON public.fin_ex_chat_run_t (tenant_id, user_id, session_id) "
                        + "WHERE status = 'RUNNING'"
        ));

        assertThatThrownBy(() -> validator(rows).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("列或状态条件不符合");
    }

    @Test
    void activeRunIndexWithNegatedPredicateStopsStartup() {
        List<Map<String, Object>> rows = List.of(Map.of(
                "is_unique", true,
                "is_valid", true,
                "index_definition", "CREATE UNIQUE INDEX uk_fin_ex_chat_run_active_session "
                        + "ON public.fin_ex_chat_run_t (tenant_id, user_id, session_id) "
                        + "WHERE status NOT IN ('RUNNING', 'CANCELLING')"
        ));

        assertThatThrownBy(() -> validator(rows).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("列或状态条件不符合");
    }

    private FinanceExDatabaseSchemaValidator validator(List<Map<String, Object>> rows) {
        return new FinanceExDatabaseSchemaValidator(new StubJdbcTemplate(rows));
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final List<Map<String, Object>> rows;

        private StubJdbcTemplate(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return rows;
        }
    }
}
