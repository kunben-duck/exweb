package com.huawei.finance.front.one.infrastructure.redis;

import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * FinanceEX Redis key 命名空间。
 *
 * <p>配置文件中的 Redis prefix 仍保持逻辑前缀，例如 {@code fin_ex:chat_run:active}。
 * 本组件在运行时把 Spring active profile 注入为第二段，得到
 * {@code fin_ex:{env}:chat_run:active}，从而隔离同一 Redis 集群中的不同部署环境。</p>
 */
@Component
public class FinanceExRedisKeyNamespace {
    private static final String ROOT_PREFIX = "fin_ex";
    private static final String DEFAULT_ENV = "default";

    private final String env;

    public FinanceExRedisKeyNamespace(Environment environment) {
        this(resolveEnv(environment));
    }

    private FinanceExRedisKeyNamespace(String env) {
        this.env = normalizeEnv(env);
    }

    /**
     * 创建指定环境的 namespace，主要用于单元测试。
     *
     * @param env 环境标识。
     * @return Redis key namespace。
     */
    public static FinanceExRedisKeyNamespace ofEnv(String env) {
        return new FinanceExRedisKeyNamespace(env);
    }

    /**
     * @return 当前 Redis key 使用的环境标识。
     */
    public String env() {
        return env;
    }

    /**
     * 给逻辑 Redis prefix 注入环境段。
     *
     * @param logicalPrefix 逻辑 prefix，必须以 {@code fin_ex} 或 {@code fin_ex:} 开头。
     * @return 带环境隔离的 prefix。
     */
    public String prefix(String logicalPrefix) {
        if (logicalPrefix == null || logicalPrefix.isBlank()) {
            throw new IllegalArgumentException("Redis key prefix 不能为空");
        }
        String trimmed = logicalPrefix.trim();
        if (!ROOT_PREFIX.equals(trimmed) && !trimmed.startsWith(ROOT_PREFIX + ":")) {
            throw new IllegalArgumentException("Redis key prefix 必须以 fin_ex 开头: " + logicalPrefix);
        }
        return ROOT_PREFIX + ":" + env + trimmed.substring(ROOT_PREFIX.length());
    }

    private static String resolveEnv(Environment environment) {
        if (environment == null || environment.getActiveProfiles().length == 0) {
            return DEFAULT_ENV;
        }
        return environment.getActiveProfiles()[0];
    }

    private static String normalizeEnv(String value) {
        String text = value == null || value.isBlank() ? DEFAULT_ENV : value.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return text.isBlank() ? DEFAULT_ENV : text;
    }
}
