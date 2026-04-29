package com.huawei.finance.front.one.application.config;

import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.domain.routing.RoutingPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationBeanConfig {
    @Bean
    public RoutingPolicy routingPolicy(@Value("${financeex.use-case-library.min-score:0.85}") double minScore) {
        return new RoutingPolicy(minScore);
    }

    @Bean public PermissionChecker permissionChecker() { return new PermissionChecker(); }
}
