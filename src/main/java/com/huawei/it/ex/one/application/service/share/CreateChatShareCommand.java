/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.share;

import java.time.Instant;

/**
 * 创建分享命令。
 *
 * @param messageId 被分享的 assistant 消息 ID。
 * @param title 分享标题；为空时使用父 user 问题生成。
 * @param expiresAt 分享过期时间；为空表示不过期。
 */
public record CreateChatShareCommand(
        String messageId,
        String title,
        Instant expiresAt
) {
    static CreateChatShareCommand empty() {
        return new CreateChatShareCommand(null, null, null);
    }
}
