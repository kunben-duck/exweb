/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多消息分享快照限制。
 */
@Component
@ConfigurationProperties(prefix = "financeex.share.selected-messages")
public class ChatShareSelectedMessagesProperties {
    /** 单次请求允许提交的原始消息 ID 数量。 */
    private int maxMessages = 50;
    /** 序列化后固定快照允许占用的最大 UTF-8 字节数。 */
    private long maxSnapshotBytes = 5L * 1024L * 1024L;

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public long getMaxSnapshotBytes() {
        return maxSnapshotBytes;
    }

    public void setMaxSnapshotBytes(long maxSnapshotBytes) {
        this.maxSnapshotBytes = maxSnapshotBytes;
    }

    public int requiredMaxMessages() {
        return Math.max(1, maxMessages);
    }

    public long requiredMaxSnapshotBytes() {
        return Math.max(1L, maxSnapshotBytes);
    }
}
