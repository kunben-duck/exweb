/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.session;

import com.huawei.it.ex.one.application.config.SessionSearchProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 在有界只读事务内执行会话关键字搜索；游标搜索不查询总数。 */
@Component
@EnableConfigurationProperties(SessionSearchProperties.class)
public class SessionPageKeywordSearchExecutor {
    private final ChatSessionMapper mapper;

    public SessionPageKeywordSearchExecutor(ChatSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.session-search.database-query-timeout-seconds:2}"
    )
    public Result search(Query query) {
        long totalRows = mapper.countPageByOwner(
                query.tenantId(), query.userId(), query.appId(), query.keywordPattern(),
                query.channel(), query.mainSiteOnly());
        List<ChatSessionRow> rows = totalRows == 0 || query.offset() >= totalRows
                ? List.of()
                : mapper.findNumberPageByOwner(
                        query.tenantId(), query.userId(), query.appId(), query.keywordPattern(),
                        query.channel(), query.mainSiteOnly(), query.limit(), query.offset());
        return new Result(totalRows, rows);
    }

    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.session-search.database-query-timeout-seconds:2}"
    )
    public List<ChatSessionRow> searchCursor(CursorQuery query) {
        return mapper.findPageByOwner(
                query.tenantId(), query.userId(), query.appId(), query.titlePattern(), query.keywordPattern(),
                query.channel(), query.mainSiteOnly(), query.cursorUpdatedAt(), query.cursorId(), query.limit());
    }

    public record CursorQuery(
            String tenantId,
            String userId,
            String appId,
            String titlePattern,
            String keywordPattern,
            String channel,
            boolean mainSiteOnly,
            Instant cursorUpdatedAt,
            String cursorId,
            int limit
    ) {}

    public record Query(
            String tenantId,
            String userId,
            String appId,
            String keywordPattern,
            String channel,
            boolean mainSiteOnly,
            int limit,
            long offset
    ) {}

    public record Result(long totalRows, List<ChatSessionRow> rows) {
        public Result {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}
