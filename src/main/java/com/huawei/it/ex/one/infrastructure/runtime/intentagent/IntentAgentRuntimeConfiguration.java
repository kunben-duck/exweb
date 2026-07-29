package com.huawei.it.ex.one.infrastructure.runtime.intentagent;

import com.huawei.it.ex.one.application.integration.intent.IntentAgentRuntime;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamClient;
import com.huawei.it.ex.one.application.integration.intent.IntentService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects exactly one IntentAgent runtime protocol when intent routing is enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "financeex.intent", name = "enabled", havingValue = "true")
public class IntentAgentRuntimeConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "financeex.intent", name = "invocation-mode",
            havingValue = "BLOCKING")
    public IntentAgentRuntime blockingIntentAgentRuntime(IntentService intentService) {
        return new BlockingIntentAgentRuntime(intentService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "financeex.intent", name = "invocation-mode",
            havingValue = "STREAMING", matchIfMissing = true)
    public IntentAgentRuntime streamingIntentAgentRuntime(IntentDecisionStreamClient streamClient) {
        return new StreamingIntentAgentRuntime(streamClient);
    }
}
