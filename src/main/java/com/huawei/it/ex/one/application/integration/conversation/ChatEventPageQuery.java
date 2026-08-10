package com.huawei.it.ex.one.application.integration.conversation;

/** stop fallback按run和seq分页读取事件的归属查询。 */
public record ChatEventPageQuery(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        long afterSeq,
        int limit
) {
    public ChatEventPageQuery {
        afterSeq = Math.max(0L, afterSeq);
        limit = Math.max(1, limit);
    }
}
