package com.huawei.it.ex.one.document.domain;

/**
 * 文档库查询条件。
 *
 * @param sessionId 会话标识；为空表示查询当前用户的全部文档库。
 * @param limit 本次最多返回的文档数量。
 * @param cursor 上一次查询返回的分页游标。
 */
public record DocumentLibraryQuery(
        String sessionId,
        int limit,
        String cursor
) {
    /**
     * @return 归一化后的页大小，防止前端传入过大的 limit 压垮数据库。
     */
    public int normalizedLimit() {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }
}
