package com.huawei.it.ex.one.share.interfaces.dto;

import java.time.Instant;

/**
 * 创建单轮问答分享请求。
 *
 * @param title 分享标题；为空时服务端使用父 user 问题生成。
 * @param expiresAt 分享过期时间；为空表示不过期，传入时必须晚于当前时间。
 */
public record CreateChatShareRequest(
        String title,
        Instant expiresAt
) {}
