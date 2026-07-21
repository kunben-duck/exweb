package com.huawei.it.ex.one.security.application.context;

import com.huawei.it.ex.one.security.domain.UserContext;

/**
 * 当前调用方身份上下文提供者。
 *
 * <p>这是协议入口访问企业权限体系的唯一防腐层。Controller、WebSocket、上传接口等入口不再从前端
 * 接收 tenantId/userId，而是在请求入口解析一次 {@link UserContext} 后作为不可变参数传入应用层。
 * 后续接入公司统一权限框架时，只需要替换该 port 的实现。</p>
 */
public interface AuthContextProvider {
    /**
     * 解析当前请求或连接的用户身份。
     *
     * @return 当前用户身份上下文；无法解析 tenantId/userId 时实现方应直接抛出异常。
     */
    UserContext resolve();
}
