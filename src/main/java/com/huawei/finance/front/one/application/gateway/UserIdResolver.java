package com.huawei.finance.front.one.application.gateway;

/**
 * 用户 ID 解析接口。
 *
 * <p>这是生产身份体系的扩展点，application 层只依赖该抽象，不关心 userId 来自前端、服务端 Session 还是 SSO Token。</p>
 */
public interface UserIdResolver {
    String resolveUserId(UserIdResolveRequest request);
}
