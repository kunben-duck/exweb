package com.huawei.finance.front.one.infrastructure.security;

import com.huawei.finance.front.one.application.gateway.UserIdResolveRequest;
import com.huawei.finance.front.one.application.gateway.UserIdResolver;
import org.springframework.stereotype.Component;

/**
 * 前端透传用户 ID 的临时实现。
 *
 * <p>后续切换服务端 Session 上下文时，只需要替换该实现，不需要改聊天、会话或 Agent 编排代码。</p>
 */
@Component
public class FrontendUserIdResolver implements UserIdResolver {
    @Override
    public String resolveUserId(UserIdResolveRequest request) {
        String frontendUserId = request == null ? null : request.frontendUserId();
        return frontendUserId == null || frontendUserId.isBlank() ? "anonymous" : frontendUserId;
    }
}
