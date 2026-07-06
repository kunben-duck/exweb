package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.chat.ChatHitlRequest;
import java.util.Map;

/**
 * HITL 响应 claim 结果。
 *
 * @param request claim 后的等待请求快照。
 * @param responsePayload 用户响应 payload。
 */
public record ChatHitlClaimResult(ChatHitlRequest request, Map<String, Object> responsePayload) {
}
