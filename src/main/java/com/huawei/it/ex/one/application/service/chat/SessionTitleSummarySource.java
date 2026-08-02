package com.huawei.it.ex.one.application.service.chat;

/** 会话标题的可信来源。 */
enum SessionTitleSummarySource {
    DEFAULT,
    AUTO,
    USER,
    LOCKED;

    boolean autoReplaceable() {
        return this == DEFAULT || this == AUTO;
    }
}
