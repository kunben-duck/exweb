package com.huawei.finance.front.one.application.integration.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessageFeedback;

/**
 * 聊天消息反馈事实源端口。
 */
public interface ChatFeedbackRepository {
    /**
     * 保存用户反馈。
     *
     * @param feedback 反馈事实。
     * @return 已保存的反馈。
     */
    ChatMessageFeedback save(ChatMessageFeedback feedback);
}
