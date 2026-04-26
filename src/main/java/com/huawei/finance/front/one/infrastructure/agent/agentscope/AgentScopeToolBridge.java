package com.huawei.finance.front.one.infrastructure.agent.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.gateway.AgentRunRequest;
import com.huawei.finance.front.one.application.service.ToolGatewayApplicationService;
import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 工具桥。
 *
 * <p>Agent 只看到 invoke_finance_tool 一个工具入口；真实工具选择、权限和审计仍由统一网关处理。</p>
 */
public class AgentScopeToolBridge {
    private final AgentRunRequest runRequest;
    private final ToolGatewayApplicationService toolGateway;
    private final ObjectMapper objectMapper;

    public AgentScopeToolBridge(AgentRunRequest runRequest, ToolGatewayApplicationService toolGateway, ObjectMapper objectMapper) {
        this.runRequest = runRequest; this.toolGateway = toolGateway; this.objectMapper = objectMapper;
    }

    @Tool(name = "invoke_finance_tool", description = "通过 FinanceEX 统一工具网关调用财经工具。参数 toolCode 为工具编码，argumentsJson 为 JSON 字符串。")
    public String invokeFinanceTool(
            @ToolParam(name = "toolCode", description = "工具编码，例如 finance.office.query") String toolCode,
            @ToolParam(name = "argumentsJson", description = "工具入参 JSON 字符串") String argumentsJson) {
        try {
            // AgentScope 工具参数是字符串，这里统一解析为 JsonNode 再交给工具网关。
            JsonNode args = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            ToolInvokeCommand command = new ToolInvokeCommand(runRequest.tenantId(), runRequest.userId(), runRequest.sessionId(), runRequest.runId(), toolCode, runRequest.runId() + "-" + toolCode, args, false, "agent", Map.of("source", "agentscope"));
            // 这里处于 AgentScope 阻塞调用栈内，收集事件后返回 JSON 字符串给模型观察。
            List<ToolInvocationEvent> events = toolGateway.invoke(command).collectList().block();
            return objectMapper.writeValueAsString(events == null ? List.of() : events);
        } catch (Exception e) {
            return "{\"status\":\"FAILED\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
