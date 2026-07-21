package com.huawei.it.ex.one.share.application.service;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.share.domain.ChatShareDelivery;

/** Application boundary for delivering an existing share snapshot. */
public interface ChatShareDeliveryService {
    ChatShareDelivery deliver(UserContext user, CreateChatShareDeliveryCommand command);
}
