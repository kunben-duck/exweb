package com.huawei.finance.front.one.application.config;

import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.domain.routing.RoutingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationBeanConfig {
    @Bean public RoutingPolicy routingPolicy() { return new RoutingPolicy(); }
    @Bean public PermissionChecker permissionChecker() { return new PermissionChecker(); }
}
