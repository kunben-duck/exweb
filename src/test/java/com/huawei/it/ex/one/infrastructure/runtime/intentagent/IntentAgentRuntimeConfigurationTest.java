/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime.intentagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.intent.IntentAgentRuntime;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamClient;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamFrame;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.application.integration.intent.IntentService;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;

import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

class IntentAgentRuntimeConfigurationTest {
    private final IntentDecision decision = new IntentDecision(
            "intent-1", "知识问答", TaskComplexity.SIMPLE, 0.9, true,
            "skill-1", Map.of(), List.of(), Map.of());
    private final IntentService blockingService = (command, memory, user) -> decision;
    private final IntentDecisionStreamClient streamingClient = (command, memory, user) -> Flux.just(
            IntentDecisionStreamFrame.result(IntentRecognitionResult.finalDecision(decision), 1, 1));

    @Test
    void selectsStreamingRuntimeByDefault() {
        contextRunner()
                .withPropertyValues("financeex.intent.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(IntentAgentRuntime.class);
                    assertThat(context.getBean(IntentAgentRuntime.class))
                            .isInstanceOf(StreamingIntentAgentRuntime.class);
                });
    }

    @Test
    void selectsBlockingRuntimeExplicitly() {
        contextRunner()
                .withPropertyValues(
                        "financeex.intent.enabled=true",
                        "financeex.intent.invocation-mode=BLOCKING")
                .run(context -> {
                    assertThat(context).hasSingleBean(IntentAgentRuntime.class);
                    assertThat(context.getBean(IntentAgentRuntime.class))
                            .isInstanceOf(BlockingIntentAgentRuntime.class);
                });
    }

    @Test
    void registersNoRuntimeWhenIntentIsDisabled() {
        contextRunner()
                .withPropertyValues("financeex.intent.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IntentAgentRuntime.class));
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(IntentAgentRuntimeConfiguration.class)
                .withBean(IntentService.class, () -> blockingService)
                .withBean(IntentDecisionStreamClient.class, () -> streamingClient);
    }
}
