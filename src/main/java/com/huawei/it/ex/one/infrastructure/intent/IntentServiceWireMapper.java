package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * 财经 Eureka 意图服务 wire 协议转换门面。
 *
 * <p>该门面把请求、响应 mapper 组合成一个适配器依赖，避免 HTTP 调用类同时暴露多个 wire
 * 细节组件；后续下游入参或出参变化仍只收敛在 mapper 层。</p>
 */
@Component
public class IntentServiceWireMapper {
    private final IntentServiceRequestMapper requestMapper;
    private final IntentServiceResponseMapper responseMapper;

    public IntentServiceWireMapper(IntentServiceRequestMapper requestMapper,
                                   IntentServiceResponseMapper responseMapper) {
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
    }

    /**
     * 构造意图服务请求体。
     *
     * @param command 本轮聊天命令。
     * @param memory 可选记忆上下文。
     * @param user 请求入口固化后的用户上下文。
     * @return 意图服务 HTTP 请求体。
     */
    public IntentRecognizeRequest toWireRequest(ChatCommand command, MemoryContext memory, UserContext user) {
        return requestMapper.toWireRequest(command, memory, user);
    }

    /**
     * 将意图服务响应转换为稳定领域模型。
     *
     * @param root 下游响应 JSON。
     * @return 领域意图决策。
     */
    public IntentDecision toDecision(JsonNode root) {
        return responseMapper.toDecision(root);
    }

    public IntentRecognitionResult toRecognitionResult(JsonNode root) {
        return responseMapper.toRecognitionResult(root);
    }

    /**
     * 构造意图服务不可用时的降级决策。
     *
     * @param reason 降级原因摘要。
     * @return 降级后的领域意图决策。
     */
    public IntentDecision degraded(String reason) {
        return responseMapper.degraded(reason);
    }
}
