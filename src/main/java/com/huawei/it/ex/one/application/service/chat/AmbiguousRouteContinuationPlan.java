package com.huawei.it.ex.one.application.service.chat;

/**
 * AMBIGUOUS_ROUTE 续接在 claim 前解析出的可信执行计划。
 */
record AmbiguousRouteContinuationPlan(
        Mode mode,
        AmbiguousRouteSelectionResolver.Candidate candidate,
        String routeSource,
        String selectionSource
) {
    enum Mode {
        SELECT_CANDIDATE,
        AUTO_SELECT,
        OTHER
    }

    static AmbiguousRouteContinuationPlan selected(
            AmbiguousRouteSelectionResolver.Candidate candidate) {
        return new AmbiguousRouteContinuationPlan(
                Mode.SELECT_CANDIDATE,
                candidate,
                "user-confirmed",
                AmbiguousRouteSupport.SELECTION_SOURCE_USER);
    }

    static AmbiguousRouteContinuationPlan autoSelected(
            AmbiguousRouteSelectionResolver.Candidate candidate) {
        return new AmbiguousRouteContinuationPlan(
                Mode.AUTO_SELECT,
                candidate,
                "user-delegated-auto-selected",
                AmbiguousRouteSupport.SELECTION_SOURCE_DELEGATED);
    }

    static AmbiguousRouteContinuationPlan other() {
        return new AmbiguousRouteContinuationPlan(
                Mode.OTHER,
                null,
                null,
                AmbiguousRouteSupport.SELECTION_SOURCE_USER);
    }

    boolean selectedCandidate() {
        return mode == Mode.SELECT_CANDIDATE || mode == Mode.AUTO_SELECT;
    }
}
