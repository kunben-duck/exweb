package com.huawei.finance.front.one.infrastructure.tool;

import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.application.gateway.ToolInvoker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import com.huawei.finance.front.one.domain.tool.ToolInvocationCompletedEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvocationStartedEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class MockThirdPartyToolInvoker implements ToolInvoker {
    private final IdGenerator idGenerator;
    public MockThirdPartyToolInvoker(IdGenerator idGenerator) { this.idGenerator = idGenerator; }
    @Override public boolean supports(ToolDefinition tool) { return "mock-third-party".equals(tool.providerCode()); }
    @Override public Flux<ToolInvocationEvent> invoke(ToolDefinition tool, ToolInvokeCommand command, UserContext user) {
        String invocationId = idGenerator.newId("toolrun", IdGenerateContext.of(user.tenantId(), user.userId(), command.sessionId(), command.runId()));
        Map<String, Object> output = Map.of(
                "toolCode", tool.toolCode(),
                "provider", tool.providerCode(),
                "arguments", command.arguments(),
                "mockResult", "第三方工具调用成功"
        );
        return Flux.just(ToolInvocationStartedEvent.of(invocationId, tool.toolCode()), ToolInvocationCompletedEvent.of(invocationId, tool.toolCode(), output));
    }
    @Override public String implementorCode() { return "mock-third-party-http"; }
}
