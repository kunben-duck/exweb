package com.huawei.it.ex.one.runtime.application.model;

/** Immutable selected route consumed by Runtime execution. */
public record RuntimeRouteSnapshot(
        RuntimeRouteType type,
        String selectedAgentCode,
        String routeSource,
        double score,
        String reason
) {
}
