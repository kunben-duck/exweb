package com.huawei.finance.front.one.infrastructure.agent.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.gateway.AgentEngine;
import com.huawei.finance.front.one.application.gateway.AgentEngineType;
import com.huawei.finance.front.one.application.gateway.AgentRunRequest;
import com.huawei.finance.front.one.application.service.ToolGatewayApplicationService;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.infrastructure.agent.agentscope.memory.AgentScopeMemoryFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AgentScope 本地 Agent 引擎实现。
 *
 * <p>application 层只依赖 AgentEngine，AgentScope 相关 API 被限制在 infrastructure 包内。</p>
 */
@Component
@EnableConfigurationProperties(AgentScopeProperties.class)
public class AgentScopeAgentEngine implements AgentEngine {
    private final AgentScopeProperties properties;
    private final AgentScopePromptAssembler promptAssembler;
    private final AgentScopeMemoryFactory memoryFactory;
    private final ToolGatewayApplicationService toolGateway;
    private final ObjectMapper objectMapper;

    public AgentScopeAgentEngine(AgentScopeProperties properties, AgentScopePromptAssembler promptAssembler, AgentScopeMemoryFactory memoryFactory,
                                        ToolGatewayApplicationService toolGateway, ObjectMapper objectMapper) {
        this.properties = properties; this.promptAssembler = promptAssembler; this.memoryFactory = memoryFactory; this.toolGateway = toolGateway; this.objectMapper = objectMapper;
    }

    @Override public AgentEngineType engineType() { return AgentEngineType.AGENTSCOPE; }
    @Override public boolean supports(AgentEngineType engineType) { return engineType == AgentEngineType.AGENTSCOPE; }

    @Override
    public Flux<ChatEvent> run(AgentRunRequest request) {
        if (responseMode(request) == ChatResponseMode.STREAM) {
            return runStreaming(request);
        }
        return runBlocking(request);
    }

    private Flux<ChatEvent> runBlocking(AgentRunRequest request) {
        // block 模式等待 AgentScope 完整回复，再一次性映射为 message.delta。
        return Mono.fromCallable(() -> executeBlocking(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(text -> Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), text),
                        MessageCompletedEvent.of(request.runId(), request.sessionId())
                ));
    }

    private Flux<ChatEvent> runStreaming(AgentRunRequest request) {
        return Flux.defer(() -> {
            ReActAgent agent = buildAgent(request);
            Msg msg = currentUserMessage(request);
            StreamOptions options = StreamOptions.builder()
                    .eventTypes(EventType.AGENT_RESULT)
                    .incremental(true)
                    .includeReasoningChunk(false)
                    .includeReasoningResult(false)
                    .includeActingChunk(false)
                    .includeSummaryChunk(false)
                    .includeSummaryResult(false)
                    .build();

            // stream 模式直接使用 AgentScope 原生流式事件，逐段转换为前端可消费的 message.delta。
            return agent.stream(msg, options)
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(this::extractText)
                    .filter(text -> text != null && !text.isBlank())
                    .map(text -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), text))
                    .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
        });
    }

    private String executeBlocking(AgentRunRequest request) {
        ReActAgent agent = buildAgent(request);
        Msg msg = currentUserMessage(request);
        // block 模式使用 AgentScope call 获取完整消息。
        Msg response = agent.call(msg).block();
        return response == null ? "" : response.getTextContent();
    }

    private ReActAgent buildAgent(AgentRunRequest request) {
        // 只注册一个统一工具桥，具体工具权限和审计仍由 ToolGatewayApplicationService 负责。
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AgentScopeToolBridge(request, toolGateway, objectMapper));

        // OpenAIChatModel 使用兼容 OpenAI 协议的 baseUrl，方便接入内网模型网关。
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .baseUrl(properties.getBaseUrl())
                .build();
        // 每次运行新建 ReActAgent，确保 prompt、工具上下文和 run 级变量不串用。
        ReActAgent agent = ReActAgent.builder()
                .name("FinanceEXLocalAgent")
                .sysPrompt(promptAssembler.systemPrompt(request))
                .memory(memoryFactory.shortTermMemory(request))
                .longTermMemory(memoryFactory.longTermMemory(request))
                .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                .model(model)
                .toolkit(toolkit)
                .maxIters(properties.getMaxIters())
                .build();
        return agent;
    }

    private Msg currentUserMessage(AgentRunRequest request) {
        // 明确标记为 USER，确保 AgentScope 的长期记忆 Hook 能基于最后一条用户消息做检索。
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(request.message())
                .metadata(currentMessageMetadata(request))
                .build();
    }

    private Map<String, Object> currentMessageMetadata(AgentRunRequest request) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "current-request");
        if (request.tenantId() != null) metadata.put("tenantId", request.tenantId());
        if (request.userId() != null) metadata.put("userId", request.userId());
        if (request.sessionId() != null) metadata.put("sessionId", request.sessionId());
        if (request.runId() != null) metadata.put("runId", request.runId());
        return metadata;
    }

    private String extractText(Event event) {
        if (event == null || event.getMessage() == null) {
            return null;
        }
        return event.getMessage().getTextContent();
    }

    private ChatResponseMode responseMode(AgentRunRequest request) {
        return request.responseMode() == null ? ChatResponseMode.BLOCK : request.responseMode();
    }
}
