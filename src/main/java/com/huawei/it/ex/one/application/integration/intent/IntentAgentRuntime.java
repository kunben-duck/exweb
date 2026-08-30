/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

import reactor.core.publisher.Flux;

/**
 * 意图路由下游 Agent 防腐层。
 *
 * <p>IntentAgent 是路由阶段 Agent：它会返回前端可见的标准事件和最终意图识别结果，
 * 但不创建普通 RuntimeBinding，也不直接生成 assistant 正文。</p>
 */
public interface IntentAgentRuntime {
    String PROVIDER = "intent-agent";

    Flux<IntentAgentRouteFrame> route(IntentAgentRouteRequest request);
}
