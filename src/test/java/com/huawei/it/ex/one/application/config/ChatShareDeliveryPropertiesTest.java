/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ChatShareDeliveryPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void mobileShareLinkIsDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatShareDeliveryProperties.class).normalizedMobileShareUrlPrefix()).isEmpty();
        });
    }

    @Test
    void bindsAndTrimsTheMobileSharePrefix() {
        runner.withPropertyValues("financeex.share.mobile-share-url-prefix= h5://app/#/share?shareId= ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatShareDeliveryProperties.class).normalizedMobileShareUrlPrefix())
                            .isEqualTo("h5://app/#/share?shareId=");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatShareDeliveryProperties.class)
    static class TestConfiguration {
    }
}
