package com.huawei.it.ex.one.infrastructure.session;

import com.huawei.it.ex.one.application.config.SessionSearchProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 在单个只读超时事务内完成页码搜索的总数和数据查询。 */
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
