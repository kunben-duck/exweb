package com.huawei.it.ex.one.application.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 可选路由信号配置。
 *
 * <p>v3 架构中，用例库和意图服务只是 SuperAgent 的外部路由信号，不再是聊天主链路的强依赖。
 * 该配置集中控制是否允许编排层调用对应外部 HTTP API。默认关闭，确保未部署这些服务时请求会直接进入
 * Relay Runtime。</p>
 */
@Component
public class RouteSignalProperties {
    /** 是否启用用例库匹配信号；关闭时不会调用 UseCaseLibraryClient。 */
    private final boolean useCaseLibraryEnabled;
    /** 是否启用意图识别信号；关闭时不会调用 intent-agent。 */
    private final boolean intentEnabled;
    /** 意图技术或协议失败在重试耗尽后的处理策略。 */
    private final IntentFailureStrategy intentFailureStrategy;

    /**
     * 创建路由信号配置。
     *
     * @param useCaseLibraryEnabled true 表示允许调用用例库服务。
     * @param intentEnabled true 表示允许调用意图服务。
     */
    @Autowired
    public RouteSignalProperties(
            @Value("${financeex.use-case-library.enabled:false}") boolean useCaseLibraryEnabled,
            @Value("${financeex.intent.enabled:false}") boolean intentEnabled,
            @Value("${financeex.intent.failure-strategy:RELAY_FALLBACK}")
            String intentFailureStrategy) {
        this(useCaseLibraryEnabled, intentEnabled, parseFailureStrategy(intentFailureStrategy));
    }

    public RouteSignalProperties(boolean useCaseLibraryEnabled, boolean intentEnabled,
                                 IntentFailureStrategy intentFailureStrategy) {
        this.useCaseLibraryEnabled = useCaseLibraryEnabled;
        this.intentEnabled = intentEnabled;
        this.intentFailureStrategy = intentFailureStrategy == null
                ? IntentFailureStrategy.RELAY_FALLBACK
                : intentFailureStrategy;
    }

    /**
     * 测试和纯 Java 调用兼容构造器，保持历史默认 Relay 降级语义。
     */
    public RouteSignalProperties(boolean useCaseLibraryEnabled, boolean intentEnabled) {
        this(useCaseLibraryEnabled, intentEnabled, IntentFailureStrategy.RELAY_FALLBACK);
    }

    /**
     * @return true 表示首轮路由可以调用用例库。
     */
    public boolean useCaseLibraryEnabled() {
        return useCaseLibraryEnabled;
    }

    /**
     * @return true 表示首轮路由可以调用意图服务。
     */
    public boolean intentEnabled() {
        return intentEnabled;
    }

    public IntentFailureStrategy intentFailureStrategy() {
        return intentFailureStrategy;
    }

    private static IntentFailureStrategy parseFailureStrategy(String value) {
        if (value == null || value.isBlank()) {
            return IntentFailureStrategy.RELAY_FALLBACK;
        }
        return switch (value.trim()) {
            case "RELAY_FALLBACK" -> IntentFailureStrategy.RELAY_FALLBACK;
            case "FAIL_RUN" -> IntentFailureStrategy.FAIL_RUN;
            default -> throw new IllegalArgumentException("financeex.intent.failure-strategy 仅支持 "
                    + "RELAY_FALLBACK 或 FAIL_RUN，当前值: " + value);
        };
    }

}
