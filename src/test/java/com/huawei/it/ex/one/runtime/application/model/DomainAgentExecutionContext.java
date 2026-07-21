package com.huawei.it.ex.one.runtime.application.model;

import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.security.domain.UserContext;

/**
 * DomainAgent 调用执行上下文。
 */
public record DomainAgentExecutionContext(
        RuntimeCommandSnapshot command,
        String runId,
        RuntimeRouteSnapshot route,
        UserContext user,
        RuntimeBinding binding,
        RuntimeForwardHeaders forwardHeaders
) {
}
