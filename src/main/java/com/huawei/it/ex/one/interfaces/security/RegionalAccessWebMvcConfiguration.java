package com.huawei.it.ex.one.interfaces.security;

import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the regional gate after enterprise identity interceptors and before business controllers.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RegionalAccessWebMvcConfiguration implements WebMvcConfigurer {
    private final RegionalAccessInterceptor interceptor;
    private final RegionalAccessProperties properties;

    public RegionalAccessWebMvcConfiguration(RegionalAccessInterceptor interceptor,
                                             RegionalAccessProperties properties) {
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/v1/**")
                .excludePathPatterns("/v1/chat/ws")
                .order(properties.getInterceptorOrder());
    }
}
