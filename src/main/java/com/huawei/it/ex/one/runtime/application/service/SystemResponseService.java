package com.huawei.it.ex.one.runtime.application.service;

import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.runtime.application.model.RuntimeCommandSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeIntentSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRouteSnapshot;
import reactor.core.publisher.Flux;

/** Application boundary for deterministic system responses selected by routing. */
public interface SystemResponseService {
    Flux<ChatEvent> execute(
            RuntimeCommandSnapshot command, String runId,
            RuntimeIntentSnapshot intent, RuntimeRouteSnapshot route);
}
