/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.routing.RouteTarget;

import reactor.core.publisher.Flux;

import org.springframework.stereotype.Service;
/**
 * 系统可控回复执行器。
 *
 * <p>当意图服务明确判定 unsupported，或路由没有可用下游 Agent 时，由本执行器生成稳定、
 * 可审计的系统回复，并统一转换为前端事件流。</p>
 */
@Service
public class SystemResponseExecutor {
    /**
     * 输出一次系统回复。
     */
    public Flux<ChatEvent> execute(ChatCommand command, String runId, IntentDecision intent, RouteTarget route) {
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
