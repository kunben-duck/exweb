package com.huawei.finance.front.one.infrastructure;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRecoveryPort;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.finance.front.one.application.integration.auth.SgovTokenResolver;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.application.integration.intent.IntentAgentRuntime;
import com.huawei.finance.front.one.application.integration.intent.IntentRetryPolicy;
import com.huawei.finance.front.one.application.integration.share.ChatShareAccessPolicy;
import com.huawei.finance.front.one.infrastructure.auth.DefaultSgovTokenResolver;
import com.huawei.finance.front.one.infrastructure.id.GeneratedApplicationInstanceIdProvider;
import com.huawei.finance.front.one.infrastructure.intent.DefaultIntentRetryPolicy;
import com.huawei.finance.front.one.infrastructure.runtime.UnsupportedAgentRuntimeInteraction;
import com.huawei.finance.front.one.infrastructure.runtime.UnsupportedAgentRuntimeRecoveryPort;
import com.huawei.finance.front.one.infrastructure.runtime.intentagent.NoopIntentAgentRuntime;
import com.huawei.finance.front.one.infrastructure.share.DefaultChatShareAccessPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 默认 Port 实现配置。
 *
 * <p>本项目按 Bean 角色选择注册方式：普通业务服务使用 {@code @Service/@Component/@Repository}；
 * 按配置切换的实现使用 {@code @ConditionalOnProperty/@ConditionalOnExpression}；简单的企业可替换
 * 默认 Port 实现在这里使用 {@code @Bean + @ConditionalOnMissingBean} 注册。依赖外部 SDK 的复杂
 * 默认 port，应放在对应基础设施专属配置类中。</p>
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
     * 默认 Runtime 交互续接能力。
     *
     * <p>Relay WebSocket 等支持 HITL 的 Runtime 会提供自己的 {@link AgentRuntimeInteraction} bean；
     * 其他 Runtime 默认不进入 WAITING_USER，避免保存出无法继续的等待态。</p>
     */
    @Bean
    @ConditionalOnMissingBean(AgentRuntimeInteraction.class)
    public AgentRuntimeInteraction unsupportedAgentRuntimeInteraction() {
        return new UnsupportedAgentRuntimeInteraction();
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
     * <p>默认 resolver 不返回 token。企业框架接入时可提供新的
     * {@link SgovTokenResolver} bean 覆盖。</p>
     */
    @Bean
    @ConditionalOnMissingBean(SgovTokenResolver.class)
    public SgovTokenResolver sgovTokenResolver() {
        return new DefaultSgovTokenResolver();
    }

    /**
     * 默认意图识别重试策略。
     *
     * <p>默认策略只重试 HTTP 调用降级和意图服务错误结果。不同企业环境如果需要按错误码、
     * 灰度版本或租户调整重试规则，提供新的 {@link IntentRetryPolicy} bean 即可覆盖。</p>
     */
    @Bean
    @ConditionalOnMissingBean(IntentRetryPolicy.class)
    public IntentRetryPolicy intentRetryPolicy() {
        return new DefaultIntentRetryPolicy();
    }

    /**
     * 默认 intent-agent 兜底。
     *
     * <p>真实 HTTP IntentAgent 只在 financeex.intent.enabled=true 时注册；意图关闭时主流程不会调用
     * intent-agent，此 bean 仅用于保持应用层依赖完整。</p>
     */
    @Bean
    @ConditionalOnMissingBean(IntentAgentRuntime.class)
    @ConditionalOnProperty(prefix = "financeex.intent", name = "enabled", havingValue = "false", matchIfMissing = true)
    public IntentAgentRuntime noopIntentAgentRuntime() {
        return new NoopIntentAgentRuntime();
    }
}
