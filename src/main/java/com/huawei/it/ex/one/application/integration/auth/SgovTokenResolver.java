package com.huawei.it.ex.one.application.integration.auth;

import java.util.Optional;

/**
 * Sgov 鉴权 token 获取端口。
 *
 * <p>具体 token 协议、缓存和刷新策略由企业框架实现；本服务只依赖该端口获取
 * 可用于出站 HTTP 请求的 Authorization header 值。</p>
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
