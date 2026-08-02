package com.huawei.it.ex.one.application.service.chat;

import java.util.List;

/** 一次异步标题总结所使用的不可变消息路径快照。 */
record SessionTitleCandidate(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        List<String> queries,
        String language,
        int queryCount,
        long nodeOrder
) {
    SessionTitleCandidate {
        queries = List.copyOf(queries);
    }
}
