package com.huawei.finance.front.one.infrastructure.messaging;

import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime raw log publisher 默认实现选择配置。
 *
 * <p>raw log 是诊断旁路，项目内置默认实现为 no-op。企业 MQ 接入时提供自己的
 * {@link RuntimeRawStreamLogPublisher} bean 覆盖该默认实现即可，ChatService 主链路和
 * Relay adapter 不需要修改。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeRawStreamLogPublisherConfiguration {
    /**
     * 创建默认 raw log publisher。
     *
     * @return 空 publisher，保证 raw log 未接入企业 MQ 时不会影响聊天主链路。
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeRawStreamLogPublisher.class)
    public RuntimeRawStreamLogPublisher runtimeRawStreamLogPublisher() {
        return new NoopRuntimeRawStreamLogPublisher();
    }
}
