/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

class SessionPageKeywordSearchExecutorTest {
    @Test
    void cursorSearchUsesTheSameTimeoutAndExactlyOneQueryWithoutCount() throws Exception {
        Method method = SessionPageKeywordSearchExecutor.class.getMethod(
                "searchCursor", SessionPageKeywordSearchExecutor.CursorQuery.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.session-search.database-query-timeout-seconds:2}");
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        ChatSessionRow row = new ChatSessionRow();
        when(mapper.findPageByOwner("tenant", "user", "app", null, "%profit%", "mobile", false,
                Instant.EPOCH, "s2", 21)).thenReturn(List.of(row));

        var result = new SessionPageKeywordSearchExecutor(mapper).searchCursor(
                new SessionPageKeywordSearchExecutor.CursorQuery("tenant", "user", "app", null, "%profit%",
                        "mobile", false, Instant.EPOCH, "s2", 21));

        assertThat(result).containsExactly(row);
        verify(mapper).findPageByOwner("tenant", "user", "app", null, "%profit%", "mobile", false,
                Instant.EPOCH, "s2", 21);
        verifyNoMoreInteractions(mapper);
    }

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
