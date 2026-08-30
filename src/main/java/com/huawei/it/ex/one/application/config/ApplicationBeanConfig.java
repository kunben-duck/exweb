/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.routing.RoutingPolicy;
import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;

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
     * 创建路由策略。
     *
     * <p>用例库和意图服务都只是路由信号；意图 confidence 阈值仅保留给统计记录兼容，
     * DomainAgent 路由以意图服务 routeAction 为准。</p>
     */
    @Bean
    public RoutingPolicy routingPolicy(@Value("${financeex.use-case-library.min-score:0.85}") double minScore,
                                       @Value("${financeex.intent.confidence-threshold:0.85}") double intentConfidenceThreshold,
                                       @Value("${financeex.intent.domain-expert-access-name-prefix:}")
                                       String domainExpertAccessNamePrefix,
                                       SensitiveInformationAccessNameResolver sensitiveInformationResolver) {
        return new RoutingPolicy(minScore, intentConfidenceThreshold, domainExpertAccessNamePrefix,
                sensitiveInformationResolver);
    }

    @Bean
    public SensitiveInformationAccessNameResolver sensitiveInformationAccessNameResolver(
            @Value("${financeex.intent.sensitive-information-access-name:}") String accessName) {
        return new SensitiveInformationAccessNameResolver(accessName);
    }

    /**
     * 创建统一权限校验器。
     */
    @Bean
    public PermissionChecker permissionChecker() {
        return new PermissionChecker();
    }
}
