package com.huawei.finance.front.one.infrastructure.agent.runtime.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.gateway.AgentRuntime;
import com.huawei.finance.front.one.application.gateway.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.service.ToolGatewayApplicationService;
import com.huawei.finance.front.one.domain.agent.AgentRuntimeProvider;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.infrastructure.agent.runtime.agentscope.memory.AgentScopeMemoryFactory;
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
 * AgentScope AgentRuntime provider。
 */
@Component
@EnableConfigurationProperties(AgentScopeProperties.class)
public class AgentScopeRuntime implements AgentRuntime {
    private final AgentScopeProperties properties;
    private final AgentScopePromptAssembler promptAssembler;
    private final AgentScopeMemoryFactory memoryFactory;
    private final ToolGatewayApplicationService toolGateway;
    private final ObjectMapper objectMapper;

    public AgentScopeRuntime(AgentScopeProperties properties, AgentScopePromptAssembler promptAssembler, AgentScopeMemoryFactory memoryFactory,
                             ToolGatewayApplicationService toolGateway, ObjectMapper objectMapper) {
        this.properties = properties;
        this.promptAssembler = promptAssembler;
        this.memoryFactory = memoryFactory;
        this.toolGateway = toolGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRuntimeProvider provider() {
        return AgentRuntimeProvider.AGENTSCOPE;
    }

    @Override
    public boolean supports(AgentRuntimeProvider provider) {
        return provider == AgentRuntimeProvider.AGENTSCOPE;
    }

    @Override
    public Flux<ChatEvent> run(AgentRuntimeRequest request) {
        if (responseMode(request) == ChatResponseMode.STREAM) {
            return runStreaming(request);
        }
        return runBlocking(request);
    }

    private Flux<ChatEvent> runBlocking(AgentRuntimeRequest request) {
        return Mono.fromCallable(() -> executeBlocking(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(text -> Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), text),
                        MessageCompletedEvent.of(request.runId(), request.sessionId())
                ));
    }

    private Flux<ChatEvent> runStreaming(AgentRuntimeRequest request) {
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

            return agent.stream(msg, options)
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(this::extractText)
                    .filter(text -> text != null && !text.isBlank())
                    .map(text -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), text))
                    .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
        });
    }

    private String executeBlocking(AgentRuntimeRequest request) {
        ReActAgent agent = buildAgent(request);
        Msg response = agent.call(currentUserMessage(request)).block();
        return response == null ? "" : response.getTextContent();
    }

    private ReActAgent buildAgent(AgentRuntimeRequest request) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AgentScopeToolBridge(request, toolGateway, objectMapper));

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .baseUrl(properties.getBaseUrl())
                .build();
        return ReActAgent.builder()
                .name("FinanceEXAgentScopeRuntime")
                .sysPrompt(promptAssembler.systemPrompt(request))
                .memory(memoryFactory.shortTermMemory(request))
                .longTermMemory(memoryFactory.longTermMemory(request))
                .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                .model(model)
                .toolkit(toolkit)
                .maxIters(properties.getMaxIters())
                .build();
    }

    private Msg currentUserMessage(AgentRuntimeRequest request) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(request.message())
                .metadata(currentMessageMetadata(request))
                .build();
    }

    private Map<String, Object> currentMessageMetadata(AgentRuntimeRequest request) {
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

    private ChatResponseMode responseMode(AgentRuntimeRequest request) {
        return request.responseMode() == null ? ChatResponseMode.BLOCK : request.responseMode();
    }
}
