package com.huawei.finance.front.one.infrastructure;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRecoveryPort;
import com.huawei.finance.front.one.application.integration.auth.SgovTokenResolver;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.application.integration.share.ChatShareAccessPolicy;
import com.huawei.finance.front.one.infrastructure.auth.UnsupportedSgovTokenResolver;
import com.huawei.finance.front.one.infrastructure.id.GeneratedApplicationInstanceIdProvider;
import com.huawei.finance.front.one.infrastructure.runtime.UnsupportedAgentRuntimeRecoveryPort;
import com.huawei.finance.front.one.infrastructure.share.DefaultChatShareAccessPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 默认 Port 实现配置。
 *
 * <p>本项目按 Bean 角色选择注册方式：普通业务服务使用 {@code @Service/@Component/@Repository}；
 * 按配置切换的实现使用 {@code @ConditionalOnProperty/@ConditionalOnExpression}；简单的企业可替换
 * 默认 Port 实现在这里使用 {@code @Bean + @ConditionalOnMissingBean} 注册。像 raw log MQ publisher
 * 这类需要按外部 SDK 可用性选择实现的默认 port，会放在对应基础设施专属配置类中。</p>
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

    /**
     * 默认分享访问策略。
     *
     * <p>企业框架如需按组织、部门、用户白名单或外部 ACL 控制分享创建、查看、撤销或发送，提供新的
     * {@link ChatShareAccessPolicy} bean 即可覆盖。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ChatShareAccessPolicy.class)
    public ChatShareAccessPolicy chatShareAccessPolicy() {
        return new DefaultChatShareAccessPolicy();
    }

    /**
     * 默认 Sgov token resolver。
     *
     * <p>本服务不内置企业鉴权 token 获取逻辑；企业框架接入时提供新的
     * {@link SgovTokenResolver} bean 覆盖。</p>
     */
    @Bean
    @ConditionalOnMissingBean(SgovTokenResolver.class)
    public SgovTokenResolver sgovTokenResolver() {
        return new UnsupportedSgovTokenResolver();
    }
}
