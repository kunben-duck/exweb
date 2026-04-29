package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import reactor.core.publisher.Flux;

import org.springframework.stereotype.Service;

@Service
public class SystemResponseExecutor {
    public Flux<ChatEvent> execute(ChatCommand command, String runId, IntentDecision intent, RouteTarget route) {
        String text = intent != null && "unsupported".equals(intent.intentCode())
                ? "当前暂不支持该请求。"
                : "当前请求无法被路由到可用 Agent。";
        return Flux.just(
                (ChatEvent) MessageDeltaEvent.of(runId, command.sessionId(), text),
                (ChatEvent) MessageCompletedEvent.of(runId, command.sessionId(), "COMPLETED")
        );
    }
}
