/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 聊天消息分享详情 DTO。
 *
 * @param share 分享元数据。
 * @param question 固定快照中的父 user 问题。
 * @param answer 固定快照中的 assistant 回答。
 * @param parts 默认可见的 assistant 过程信息。
 * @param messages 多消息分享中按会话路径排序的明确选择消息；单轮分享省略。
 */
public record ChatShareDetailDto(
        ChatShareDto share,
        ChatShareSnapshotMessageDto question,
        ChatShareSnapshotMessageDto answer,
        List<ChatMessagePartDto> parts,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ChatShareSelectedMessageDto> messages
) {}
