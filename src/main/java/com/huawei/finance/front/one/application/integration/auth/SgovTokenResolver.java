package com.huawei.finance.front.one.application.integration.auth;

import java.util.Optional;

/**
 * Sgov 鉴权 token 获取端口。
 *
 * <p>默认实现不提供 token；企业框架接入时提供新的 Spring bean 覆盖该端口即可。
 * 具体 token 协议、缓存和刷新策略不在本服务中展开。</p>
 */
public interface SgovTokenResolver {
    /**
     * 获取当前出站请求可用的 Authorization header 值。
     *
     * @param request 出站请求上下文。
     * @param appId 服务 ID。
     * @param secret 服务密钥。
     * @return Authorization header 值；为空表示无法提供 token。
     */
    Optional<String> resolve(AuthHeaderRequest request, String appId, String secret);
}
