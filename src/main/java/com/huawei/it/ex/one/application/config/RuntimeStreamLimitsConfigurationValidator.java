package com.huawei.it.ex.one.application.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 校验跨配置域的Runtime流式与stop终态时间边界。 */
@Component
final class RuntimeStreamLimitsConfigurationValidator implements SmartInitializingSingleton {
    private final RuntimeStreamLimitsProperties properties;
    private final int externalTerminalTransactionTimeoutSeconds;

    RuntimeStreamLimitsConfigurationValidator(
            RuntimeStreamLimitsProperties properties,
            @Value("${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
            int externalTerminalTransactionTimeoutSeconds) {
        this.properties = properties;
        this.externalTerminalTransactionTimeoutSeconds = externalTerminalTransactionTimeoutSeconds;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Duration transactionTimeout = Duration.ofSeconds(Math.max(1, externalTerminalTransactionTimeoutSeconds));
        if (properties.getStopFinalizationLease().compareTo(transactionTimeout) <= 0) {
            throw new IllegalStateException(
                    "financeex.agent-runtime.stream-limits.stop-finalization-lease必须大于"
                            + "financeex.chat-run.external-terminal-transaction-timeout-seconds");
        }
    }
}
