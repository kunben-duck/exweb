package com.huawei.it.ex.one.application.service.share;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 创建多消息固定快照分享命令。
 */
public record CreateSelectedChatShareCommand(
        String sessionId,
        List<String> messageIds,
        String title,
        Instant expiresAt
) {
    public CreateSelectedChatShareCommand {
        messageIds = messageIds == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(messageIds));
    }
}
