/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;

import java.util.Map;

/**
 * Interaction 响应 claim 结果。
 *
 * @param request claim 后的等待请求快照。
 * @param responsePayload 用户响应 payload。
 */
public record ChatInteractionClaimResult(ChatInteractionRequest request, Map<String, Object> responsePayload) {
}
