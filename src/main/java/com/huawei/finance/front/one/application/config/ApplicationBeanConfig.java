package com.huawei.finance.front.one.application.config;

import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.routing.RoutingPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用层基础 Bean 配置。
 *
 * <p>这里只创建纯领域策略和无状态权限校验器，避免业务服务直接依赖 Spring 细节。</p>
 */
@Configuration
public class ApplicationBeanConfig {
    /**
     * 创建用例库命中阈值可配置的路由策略。
     */
    @Bean
    public RoutingPolicy routingPolicy(@Value("${financeex.use-case-library.min-score:0.85}") double minScore) {
        return new RoutingPolicy(minScore);
    }

    /**
     * 创建统一权限校验器。
     */
    @Bean
    public PermissionChecker permissionChecker() {
        return new PermissionChecker();
    }
}
