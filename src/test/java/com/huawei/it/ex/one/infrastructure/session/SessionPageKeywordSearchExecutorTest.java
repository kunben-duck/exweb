/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

class SessionPageKeywordSearchExecutorTest {
    @Test
    void countAndRowsShareReadOnlyTwoSecondTimeoutTransaction() throws Exception {
        Method method = SessionPageKeywordSearchExecutor.class.getMethod(
                "search", SessionPageKeywordSearchExecutor.Query.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.session-search.database-query-timeout-seconds:2}");
    }

    @Test
    void executesCountBeforeLoadingRows() {
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        ChatSessionRow row = new ChatSessionRow();
        row.setId("session-1");
        when(mapper.countPageByOwner(
                "tenant-1", "user-1", "app-1", "%profit%", "mobile", false))
                .thenReturn(1L);
        when(mapper.findNumberPageByOwner(
                "tenant-1", "user-1", "app-1", "%profit%", "mobile", false, 20, 0L))
                .thenReturn(List.of(row));
        SessionPageKeywordSearchExecutor executor = new SessionPageKeywordSearchExecutor(mapper);

        SessionPageKeywordSearchExecutor.Result result = executor.search(
                new SessionPageKeywordSearchExecutor.Query(
                        "tenant-1", "user-1", "app-1", "%profit%", "mobile", false, 20, 0L));

        assertThat(result.totalRows()).isEqualTo(1L);
        assertThat(result.rows()).containsExactly(row);
        verify(mapper).countPageByOwner(
                "tenant-1", "user-1", "app-1", "%profit%", "mobile", false);
        verify(mapper).findNumberPageByOwner(
                "tenant-1", "user-1", "app-1", "%profit%", "mobile", false, 20, 0L);
    }
}
