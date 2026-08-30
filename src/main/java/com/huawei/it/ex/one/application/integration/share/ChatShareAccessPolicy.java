/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.share;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatShare;

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

    /**
     * 判断当前用户是否可以把分享发送给第三方 provider。
     *
     * <p>默认复用撤销权限，保持首版“仅创建者可发送”的行为；企业框架可覆盖该方法接入
     * 部门、群组或外部 ACL。</p>
     *
     * @param user 当前登录用户。
     * @param share 待发送分享。
     * @return 是否允许发送。
     */
    default boolean canDeliver(UserContext user, ChatShare share) {
        return canRevoke(user, share);
    }
}
