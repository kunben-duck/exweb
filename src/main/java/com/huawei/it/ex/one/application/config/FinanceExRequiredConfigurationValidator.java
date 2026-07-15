package com.huawei.it.ex.one.application.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产必需配置启动校验。
 *
 * <p>该校验器运行在普通业务 bean 创建前，避免数据库、Redis 或下游 Runtime
 * 缺配置时先被框架默认值或连接池异常掩盖。可选集成只在显式启用后校验。</p>
 */
@Component
public class FinanceExRequiredConfigurationValidator
        implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<String> missing = new ArrayList<>();
        validateRequired(missing);
        validateRedis(missing);
        validateStorage(missing);
        validateAgentRuntime(missing);
        validateOptionalIntegrations(missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("FinanceEX 必需配置缺失或不完整，请显式配置后再启动:\n - "
                    + String.join("\n - ", missing));
        }
    }

    private void validateRequired(List<String> missing) {
        requireText(missing, "spring.datasource.url", "FINANCEEX_DB_URL");
        requireText(missing, "spring.datasource.username", "FINANCEEX_DB_USERNAME");
        requireText(missing, "spring.datasource.password", "FINANCEEX_DB_PASSWORD");
        requireText(missing, "financeex.websocket.allowed-origin-patterns",
                "FINANCEEX_WEBSOCKET_ALLOWED_ORIGIN_PATTERNS");
    }

    private void validateRedis(List<String> missing) {
        String mode = lower(text("financeex.redis.mode"));
        if (mode.isBlank()) {
            missing.add("financeex.redis.mode / FINANCEEX_REDIS_MODE 不能为空，可选 standalone 或 cluster");
            return;
        }
        if ("cluster".equals(mode)) {
            requireText(missing, "financeex.redis.cluster.nodes", "FINANCEEX_REDIS_CLUSTER_NODES");
            return;
        }
        if ("standalone".equals(mode)) {
            requireText(missing, "financeex.redis.host", "FINANCEEX_REDIS_HOST");
            return;
        }
        missing.add("financeex.redis.mode / FINANCEEX_REDIS_MODE 仅支持 standalone 或 cluster，当前值: " + mode);
    }

    private void validateStorage(List<String> missing) {
        String provider = lower(text("financeex.storage.provider"));
        if (provider.isBlank()) {
            missing.add("financeex.storage.provider / FINANCEEX_STORAGE_PROVIDER 不能为空，可选 local、huawei-s3、api-store");
            return;
        }
        switch (provider) {
            case "local" -> { }
            case "huawei-s3" -> {
                requireText(missing, "financeex.storage.huawei-s3.bucket", "FINANCEEX_HUAWEI_S3_BUCKET");
                requireText(missing, "financeex.storage.huawei-s3.endpoint", "FINANCEEX_HUAWEI_S3_ENDPOINT");
                requireText(missing, "financeex.storage.huawei-s3.access-key", "FINANCEEX_HUAWEI_S3_ACCESS_KEY");
                requireText(missing, "financeex.storage.huawei-s3.secret-key", "FINANCEEX_HUAWEI_S3_SECRET_KEY");
            }
            case "api-store" -> requireText(missing, "financeex.storage.api-store.base-url",
                    "FINANCEEX_API_STORE_BASE_URL");
            default -> missing.add("financeex.storage.provider / FINANCEEX_STORAGE_PROVIDER 仅支持 local、huawei-s3、api-store，当前值: "
                    + provider);
        }
    }

    private void validateAgentRuntime(List<String> missing) {
        String defaultProvider = lower(defaultText("financeex.agent-runtime.default-provider", "relay"));
        if (defaultProvider.isBlank()) {
            missing.add("financeex.agent-runtime.default-provider / FINANCEEX_AGENT_RUNTIME_DEFAULT_PROVIDER 不能为空");
            return;
        }
        boolean relayEnabled = Boolean.parseBoolean(defaultText("financeex.agent-runtime.relay.enabled", "true"));
        boolean domainAgentEnabled = enabled("financeex.domain-agent.enabled");
        switch (defaultProvider) {
            case "relay" -> {
                if (!relayEnabled) {
                    missing.add("financeex.agent-runtime.default-provider=relay 时 financeex.agent-runtime.relay.enabled 必须为 true");
                }
            }
            case "domain-agent" -> {
                if (!domainAgentEnabled) {
                    missing.add("financeex.agent-runtime.default-provider=domain-agent 时 financeex.domain-agent.enabled 必须为 true");
                }
            }
            default -> missing.add("financeex.agent-runtime.default-provider / FINANCEEX_AGENT_RUNTIME_DEFAULT_PROVIDER 不支持: "
                    + defaultProvider);
        }
        if (relayEnabled) {
            requireText(missing, "financeex.agent-runtime.relay.websocket.url", "FINANCEEX_RELAY_WS_URL");
        }
    }

    private void validateOptionalIntegrations(List<String> missing) {
        if (enabled("financeex.intent.enabled")) {
            requireText(missing, "financeex.intent.base-url", "FINANCEEX_INTENT_BASE_URL");
            requireText(missing, "financeex.intent.access-name", "FINANCEEX_INTENT_ACCESS_NAME");
        }
        if (enabled("financeex.use-case-library.enabled")) {
            requireText(missing, "financeex.use-case-library.base-url", "FINANCEEX_USE_CASE_LIBRARY_BASE_URL");
        }
        if (enabled("financeex.domain-agent.enabled")) {
            requireText(missing, "financeex.domain-agent.base-url", "FINANCEEX_DOMAIN_AGENT_BASE_URL");
        }
        if (enabled("financeex.share.delivery.providers.welink.enabled")) {
            requireText(missing, "financeex.share.share-url-prefix", "FINANCEEX_SHARE_URL_PREFIX");
            requireText(missing, "financeex.share.delivery.providers.welink.base-url",
                    "FINANCEEX_SHARE_WELINK_BASE_URL");
            requireText(missing, "financeex.share.delivery.providers.welink.send-path",
                    "FINANCEEX_SHARE_WELINK_SEND_PATH");
        }
    }

    private void requireText(List<String> missing, String property, String envName) {
        if (text(property).isBlank()) {
            missing.add(property + " / " + envName + " 不能为空");
        }
    }

    private boolean enabled(String property) {
        return Boolean.parseBoolean(defaultText(property, "false"));
    }

    private String text(String property) {
        return defaultText(property, "");
    }

    private String defaultText(String property, String fallback) {
        String value = environment == null ? null : environment.getProperty(property);
        return value == null ? fallback : value.trim();
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
