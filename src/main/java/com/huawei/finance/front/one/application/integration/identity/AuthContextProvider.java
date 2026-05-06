package com.huawei.finance.front.one.application.integration.identity;

import com.huawei.finance.front.one.domain.auth.UserContext;

/**
 * 当前调用方身份上下文提供者。
 *
 * <p>这是应用层访问企业权限体系的唯一入口。Controller、WebSocket、上传接口等协议层不再从前端
 * 接收 tenantId/userId 并向内传递，后续接入公司统一权限框架时，只需要替换该 port 的实现。</p>
 */
public interface AuthContextProvider {
    UserContext resolve();
}
