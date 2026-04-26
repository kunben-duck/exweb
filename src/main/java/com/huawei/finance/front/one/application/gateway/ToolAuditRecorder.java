package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;

public interface ToolAuditRecorder { void append(ToolInvokeCommand command, ToolDefinition tool, UserContext user, ToolInvocationEvent event); }
