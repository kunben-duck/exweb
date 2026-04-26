package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.ToolInvokeFacade;
import com.huawei.finance.front.one.application.gateway.AuthContextProvider;
import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.application.gateway.ToolAuditRecorder;
import com.huawei.finance.front.one.application.gateway.ToolInvoker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.tool.ToolConfirmationRequiredEvent;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvocationFailedEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 工具统一调用网关。
 *
 * <p>所有来自前端、本地 Agent、Relay Runtime 的工具调用都必须走这里，
 * 这样权限、确认、实现选择和审计可以集中收口。</p>
 */
@Service
public class ToolGatewayApplicationService implements ToolInvokeFacade {
    private final ToolCatalogApplicationService toolCatalog;
    private final List<ToolInvoker> implementors;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final ToolAuditRecorder audit;
    private final IdGenerator idGenerator;
    public ToolGatewayApplicationService(ToolCatalogApplicationService toolCatalog, List<ToolInvoker> implementors, AuthContextProvider auth, PermissionChecker permissionChecker, ToolAuditRecorder audit, IdGenerator idGenerator) {
        this.toolCatalog = toolCatalog; this.implementors = implementors; this.auth = auth; this.permissionChecker = permissionChecker; this.audit = audit; this.idGenerator = idGenerator;
    }
    @Override
    public Flux<ToolInvocationEvent> invoke(ToolInvokeCommand command) {
        // 每次调用都重新解析用户上下文，保证工具权限按实际调用者身份判断。
        UserContext user = auth.resolve(command.tenantId(), command.userId());
        ToolDefinition tool = toolCatalog.getTool(command.tenantId(), command.userId(), command.toolCode());
        permissionChecker.checkToolExecutable(user, tool);

        // 高风险或需要二次确认的工具先返回确认事件，调用方确认后再执行。
        if (tool.requiresConfirmation() && !command.confirmed()) {
            String invocationId = idGenerator.newId("toolrun", IdGenerateContext.of(user.tenantId(), user.userId(), command.sessionId(), command.runId()));
            return Flux.just(ToolConfirmationRequiredEvent.of(invocationId, tool.toolCode(), "该工具需要用户确认后执行"));
        }

        // providerCode/sourceType 等差异由具体 implementor 消化，应用层只选择匹配实现。
        ToolInvoker implementor = implementors.stream().filter(a -> a.supports(tool)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No ToolInvoker for " + tool.toolCode()));
        return implementor.invoke(tool, command, user)
                // 审计跟随事件流逐条记录，便于还原工具调用过程。
                .doOnNext(event -> audit.append(command, tool, user, event))
                .onErrorResume(ex -> {
                    String invocationId = idGenerator.newId("toolrun", IdGenerateContext.of(user.tenantId(), user.userId(), command.sessionId(), command.runId()));
                    return Flux.just(ToolInvocationFailedEvent.of(invocationId, tool.toolCode(), ex.getMessage()));
                });
    }
}
