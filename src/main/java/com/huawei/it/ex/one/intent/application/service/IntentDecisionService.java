package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.intent.application.model.RouteSignalFrame;
import com.huawei.it.ex.one.intent.application.model.RouteSignalRequest;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import reactor.core.publisher.Flux;

/** Application contract for resolving an executable target from the current intent context. */
public interface IntentDecisionService {

    default RouteSignalResult routeInitial(RouteSignalRequest request) {
        return routeInitialWithProgress(request)
                .filter(RouteSignalFrame::resultFrame)
                .map(RouteSignalFrame::result)
                .blockLast();
    }

    Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request);
}
