package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;
import reactor.core.publisher.Flux;

public interface ToolInvoker {
    boolean supports(ToolDefinition tool);
    Flux<ToolInvocationEvent> invoke(ToolDefinition tool, ToolInvokeCommand command, UserContext user);
    String implementorCode();
}
