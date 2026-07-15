package com.huawei.it.ex.one.infrastructure.share;

import com.huawei.it.ex.one.application.integration.share.ChatShareAccessPolicy;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatShare;

/**
 * 默认分享访问策略。
 *
 * <p>该实现只表达本服务首版默认规则：创建者必须拥有源消息；同租户登录用户可查看；
 * 只有创建者可撤销和发送。企业内部如需接部门、人员白名单或外部 ACL，可提供新的
 * {@link ChatShareAccessPolicy} bean 覆盖。</p>
 */
public class DefaultChatShareAccessPolicy implements ChatShareAccessPolicy {
    @Override
    public boolean canCreate(UserContext user, ChatMessage sourceMessage) {
        return user != null
                && sourceMessage != null
                && user.tenantId().equals(sourceMessage.tenantId())
                && user.ownerUserId().equals(sourceMessage.userId());
    }

    @Override
    public boolean canView(UserContext user, ChatShare share) {
        return user != null
                && share != null
                && user.tenantId().equals(share.tenantId());
    }

    @Override
    public boolean canRevoke(UserContext user, ChatShare share) {
        return user != null
                && share != null
                && user.tenantId().equals(share.tenantId())
                && user.ownerUserId().equals(share.ownerUserId());
    }

    @Override
    public boolean canDeliver(UserContext user, ChatShare share) {
        return canRevoke(user, share);
    }
}
