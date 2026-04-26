package com.huawei.finance.front.one.infrastructure.tool;

import com.huawei.finance.front.one.application.gateway.ToolAuditRecorder;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NoopToolAuditRecorder implements ToolAuditRecorder {
    private static final Logger log = LoggerFactory.getLogger(NoopToolAuditRecorder.class);
    @Override public void append(ToolInvokeCommand command, ToolDefinition tool, UserContext user, ToolInvocationEvent event) {
        log.info("tool audit: tenant={}, user={}, tool={}, event={}", user.tenantId(), user.userId(), tool.toolCode(), event.type());
    }
}
