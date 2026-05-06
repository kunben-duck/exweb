package com.huawei.finance.front.one.application.integration.identity;

/**
 * 用户 ID 解析接口。
 *
 * <p>这是生产身份体系的扩展点，application 层只依赖该抽象，不关心 userId 来自服务端 Session、
 * SSO Token、网关上下文还是企业内部权限 SDK。</p>
 */
public interface UserIdResolver {
    String resolveUserId(UserIdResolveRequest request);
}
