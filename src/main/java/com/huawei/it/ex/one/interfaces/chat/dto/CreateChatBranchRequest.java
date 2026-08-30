/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

/**
 * 从某条消息创建只读历史快照分支的请求。
 *
 * @param sourceMessageId 分支来源消息 ID；服务端会沿该消息回溯 active path。
 * @param title 新分支会话标题；为空时由服务端生成默认分支标题。
 */
public record CreateChatBranchRequest(
        String sourceMessageId,
        String title
) {}
