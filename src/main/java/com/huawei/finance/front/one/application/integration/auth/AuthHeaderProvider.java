package com.huawei.finance.front.one.application.integration.auth;

import java.util.Map;

/**
 * 集成服务鉴权请求头 provider。
 *
 * <p>具体鉴权协议、token 获取方式和企业框架集成都收敛在 provider 实现中，
 * 出站 HTTP adapter 只负责把返回的 header 写入请求。</p>
 */
public interface AuthHeaderProvider {
    /**
     * provider 编码。
     *
     * @return 鉴权 provider 编码，例如 none、sgov。
     */
    String providerCode();

    /**
     * 构造出站请求鉴权头。
     *
     * @param request 出站服务、操作和用户上下文。
     * @return 需要写入 HTTP 请求的 header；无鉴权时返回空 map。
     */
    Map<String, String> headers(AuthHeaderRequest request);
}
