package com.huawei.finance.front.one.infrastructure;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRecoveryPort;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.infrastructure.id.GeneratedApplicationInstanceIdProvider;
import com.huawei.finance.front.one.infrastructure.runtime.UnsupportedAgentRuntimeRecoveryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 默认 Port 实现配置。
 *
 * <p>本项目按 Bean 角色选择注册方式：普通业务服务使用 {@code @Service/@Component/@Repository}；
 * 按配置切换的实现使用 {@code @ConditionalOnProperty/@ConditionalOnExpression}；企业框架可替换的
 * 默认 Port 实现统一在这里使用 {@code @Bean + @ConditionalOnMissingBean} 注册。这样既保持普通
 * 组件扫描风格，也避免把 {@link ConditionalOnMissingBean} 放在默认实现类上导致启动期判断不稳定。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DefaultPortImplementationConfiguration {
    /**
     * 默认应用实例 ID provider。
     *
     * @param configuredInstanceId 可选固定实例 ID；为空时由默认实现生成当前进程生命周期内稳定的 ID。
     * @return 当前应用实例 ID provider。
     */
    @Bean
    @ConditionalOnMissingBean(ApplicationInstanceIdProvider.class)
    public ApplicationInstanceIdProvider applicationInstanceIdProvider(
            @Value("${financeex.instance-id:}") String configuredInstanceId) {
        return new GeneratedApplicationInstanceIdProvider(configuredInstanceId);
    }

    /**
     * 默认 Runtime recovery port。
     *
     * <p>当前正式 Runtime 不承诺可靠断点接管，因此默认返回“不支持”。如果企业 Runtime 能提供
     * resume token 和幂等输出保证，提供新的 {@link AgentRuntimeRecoveryPort} bean 即可覆盖。</p>
     *
     * @return 不支持断点接管的默认 Runtime recovery port。
     */
    @Bean
    @ConditionalOnMissingBean(AgentRuntimeRecoveryPort.class)
    public AgentRuntimeRecoveryPort unsupportedAgentRuntimeRecoveryPort() {
        return new UnsupportedAgentRuntimeRecoveryPort();
    }
}
