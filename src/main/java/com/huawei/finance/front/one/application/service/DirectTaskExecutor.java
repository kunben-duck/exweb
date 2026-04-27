package com.huawei.finance.front.one.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 简单任务直接执行器。
 */
@Service
public class DirectTaskExecutor {
    private final ToolGatewayApplicationService toolGateway;
    private final ObjectMapper objectMapper;

    public DirectTaskExecutor(ToolGatewayApplicationService toolGateway, ObjectMapper objectMapper) {
        this.toolGateway = toolGateway;
        this.objectMapper = objectMapper;
    }

    public Flux<ChatEvent> executeTool(ChatCommand command, String runId, IntentDecision intent, RouteTarget route, UserContext user) {
        if (intent != null && !intent.missingSlots().isEmpty()) {
            return completedText(runId, command.sessionId(), clarificationText(intent.missingSlots()));
        }
        ToolInvokeCommand toolCommand = new ToolInvokeCommand(
                user.tenantId(),
                user.userId(),
                command.sessionId(),
                runId,
                route.selectedToolCode(),
                runId + "-" + route.selectedToolCode(),
                objectMapper.valueToTree(intent == null ? Map.of() : intent.slots()),
                false,
                command.channel(),
                Map.of("source", "direct-tool")
        );
        return toolGateway.invoke(toolCommand)
                .collectList()
                .map(this::toolEventsText)
                .flatMapMany(text -> completedText(runId, command.sessionId(), text));
    }

    public Flux<ChatEvent> executeModel(ChatCommand command, String runId, IntentDecision intent) {
        String text;
        if (intent != null && "unsupported".equals(intent.intentCode())) {
            text = "当前暂不支持该请求。";
        } else {
            text = "已收到：" + (command.message() == null ? "" : command.message());
        }
        return completedText(runId, command.sessionId(), text);
    }

    private Flux<ChatEvent> completedText(String runId, String sessionId, String text) {
        if (text == null) {
            text = "";
        }
        ChatEvent completed = MessageCompletedEvent.of(runId, sessionId);
        if (text.isBlank()) {
            return Flux.just(completed);
        }
        return Flux.just(MessageDeltaEvent.of(runId, sessionId, text), completed);
    }

    private String clarificationText(List<String> missingSlots) {
        String joined = String.join("、", missingSlots);
        return "请补充必要参数：" + joined + "。";
    }

    private String toolEventsText(List<ToolInvocationEvent> events) {
        try {
            return objectMapper.writeValueAsString(events == null ? List.of() : events);
        } catch (JsonProcessingException e) {
            return "工具调用已完成，但结果序列化失败：" + e.getMessage();
        }
    }
}
