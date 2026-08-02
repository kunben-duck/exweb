package com.huawei.it.ex.one.application.service.chat;

/** 会话metadata中的标题总结版本状态。 */
record SessionTitleSummaryState(
        SessionTitleSummarySource source,
        int appliedQueryCount,
        long appliedNodeOrder
) {
    SessionTitleSummaryState {
        appliedQueryCount = Math.max(0, appliedQueryCount);
        appliedNodeOrder = Math.max(0L, appliedNodeOrder);
    }

    boolean olderThan(int queryCount, long nodeOrder) {
        // nodeOrder是同一会话内的单调版本；EDIT_USER可能让当前路径问题数减少，不能仅按数量拒绝新分支。
        return nodeOrder > appliedNodeOrder
                || (nodeOrder == appliedNodeOrder && queryCount > appliedQueryCount);
    }
}
