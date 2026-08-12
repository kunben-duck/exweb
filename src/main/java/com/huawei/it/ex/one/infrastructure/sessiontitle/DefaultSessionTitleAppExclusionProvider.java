package com.huawei.it.ex.one.infrastructure.sessiontitle;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleAppExclusionProvider;

import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Set;

/** 使用本地配置判断标题提炼App排除规则的默认实现。 */
public final class DefaultSessionTitleAppExclusionProvider
        implements SessionTitleAppExclusionProvider {
    private final Set<String> excludedAppIds;

    public DefaultSessionTitleAppExclusionProvider(SessionTitleProperties properties) {
        SessionTitleProperties required = Objects.requireNonNull(properties, "properties");
        this.excludedAppIds = Set.copyOf(required.getExcludedAppIds());
    }

    @Override
    public Mono<Boolean> isExcluded(String appId) {
        return Mono.just(appId != null && excludedAppIds.contains(appId));
    }
}
