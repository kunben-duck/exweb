package com.huawei.it.ex.one.runtime.application.service;

import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.MessageCompletedEvent;
import com.huawei.it.ex.one.common.event.MessageDeltaEvent;
import com.huawei.it.ex.one.runtime.application.model.RuntimeCommandSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeIntentSnapshot;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRouteSnapshot;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
/**
 * 系统可控回复执行器。
 *
 * <p>当意图服务明确判定 unsupported，或路由没有可用下游 Agent 时，由本执行器生成稳定、
 * 可审计的系统回复，并统一转换为前端事件流。</p>
 */
@Service
public class SystemResponseExecutor implements SystemResponseService {
    /**
     * 输出一次系统回复。
     */
    @Override
    public Flux<ChatEvent> execute(RuntimeCommandSnapshot command, String runId,
                                   RuntimeIntentSnapshot intent, RuntimeRouteSnapshot route) {
        String text = route != null && route.reason() != null && !route.reason().isBlank() && !"unsupported intent".equals(route.reason())
                ? route.reason()
                : intent != null && "unsupported".equals(intent.intentCode())
                ? "当前暂不支持该请求。"
                : "当前请求无法被路由到可用 Agent。";
        return Flux.just(
                (ChatEvent) MessageDeltaEvent.of(runId, command.sessionId(), text),
                (ChatEvent) MessageCompletedEvent.of(runId, command.sessionId())
        );
    }
}
