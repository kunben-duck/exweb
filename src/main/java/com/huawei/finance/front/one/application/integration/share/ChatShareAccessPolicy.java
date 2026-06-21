package com.huawei.finance.front.one.application.integration.share;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatShare;

/**
 * 分享内容访问权限防腐层。
 *
 * <p>默认实现只做同租户和创建者判断；后续接企业 ACL、部门权限或外部授权服务时，提供新的
 * Spring bean 覆盖默认实现即可，不需要修改分享表结构和接口协议。</p>
 */
public interface ChatShareAccessPolicy {
    boolean canCreate(UserContext user, ChatMessage sourceMessage);

    boolean canView(UserContext user, ChatShare share);

    boolean canRevoke(UserContext user, ChatShare share);
}
