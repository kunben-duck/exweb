package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import java.util.Map;

/**
 * Interaction 响应 claim 结果。
 *
 * @param request claim 后的等待请求快照。
 * @param responsePayload 用户响应 payload。
 */
public record ChatInteractionClaimResult(ChatInteractionRequest request, Map<String, Object> responsePayload) {
}
