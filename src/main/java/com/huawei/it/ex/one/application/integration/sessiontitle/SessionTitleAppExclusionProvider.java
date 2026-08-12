package com.huawei.it.ex.one.application.integration.sessiontitle;

import reactor.core.publisher.Mono;

/** 会话标题提炼App排除规则防腐接口。 */
public interface SessionTitleAppExclusionProvider {
    /**
     * 判断当前会话App是否应跳过标题提炼。
     *
     * @param appId 当前可信会话的AppId，主站会话可为{@code null}。
     * @return {@code true}表示跳过；{@code false}表示继续提炼。
     */
    Mono<Boolean> isExcluded(String appId);
}
