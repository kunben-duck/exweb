package com.huawei.it.ex.one.infrastructure.intent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 发送给第三方意图服务的请求 DTO。
 *
 * <p>DTO 放在 infra 层，避免把外部 HTTP 契约反向污染 application/domain。</p>
 *
 * @param messageId 触发本次意图调用的可信user消息ID；兼容调用为空时不序列化。
 * @param accessName 意图服务入口名称。
 * @param query 当前待分类用户问题；澄清回答场景填用户最新回答。
 * @param userId 用户工号或用户标识。
 * @param conversationContext 多轮路由上下文。
 * @param options 调试和扩展选项。
 */
public record IntentRecognizeRequest(
        @JsonInclude(JsonInclude.Include.NON_NULL) String messageId,
        String accessName,
        String query,
        String userId,
        Map<String, Object> conversationContext,
        Map<String, Object> options
) {
    public IntentRecognizeRequest(String accessName,
                                  String query,
                                  String userId,
                                  Map<String, Object> conversationContext,
                                  Map<String, Object> options) {
        this(null, accessName, query, userId, conversationContext, options);
    }
}
