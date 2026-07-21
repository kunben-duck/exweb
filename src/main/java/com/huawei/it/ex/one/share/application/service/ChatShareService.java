package com.huawei.it.ex.one.share.application.service;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.share.domain.ChatShare;
import com.huawei.it.ex.one.share.domain.ChatSharePage;

/** Application boundary for share snapshot lifecycle operations. */
public interface ChatShareService {
    ChatShare create(UserContext user, CreateChatShareCommand command);

    ChatShare get(UserContext user, String shareId);

    ChatShare revoke(UserContext user, String shareId);

    ChatSharePage listOwned(UserContext user, int curPage, int pageSize);
}
