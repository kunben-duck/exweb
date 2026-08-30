/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.facade;

/**
 * 会话首条 assistant 消息的轻量摘要。
 *
 * @param content assistant 消息正文；原正文为空时由应用服务规范化为空字符串。
 * @param metadataJson assistant 消息的原始 metadata JSON 字符串。
 */
public record ChatSessionFirstAssistantSummary(
        String content,
        String metadataJson
) {}
