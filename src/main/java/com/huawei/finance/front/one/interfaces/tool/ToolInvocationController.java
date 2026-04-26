package com.huawei.finance.front.one.interfaces.tool;

import com.huawei.finance.front.one.application.facade.ToolInvokeFacade;
import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;
import com.huawei.finance.front.one.interfaces.tool.dto.ToolInvokeRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 面向前端的工具调用接口。
 *
 * <p>支持一次性返回和 SSE 流式返回，两种方式都复用 ToolGatewayApplicationService。</p>
 */
@RestController
@RequestMapping("/api/v1/finance/tools")
public class ToolInvocationController {
    private final ToolInvokeFacade facade;
    public ToolInvocationController(ToolInvokeFacade facade) { this.facade = facade; }
    @PostMapping("/{toolCode}/invoke")
    public Mono<java.util.List<ToolInvocationEvent>> invoke(@PathVariable String toolCode, @RequestBody ToolInvokeRequest request,
                                                            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return facade.invoke(toCommand(toolCode, request, tenantId, userId, "front")).collectList();
    }
    @PostMapping(value = "/{toolCode}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ToolInvocationEvent>> stream(@PathVariable String toolCode, @RequestBody ToolInvokeRequest request,
                                                             @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                             @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return facade.invoke(toCommand(toolCode, request, tenantId, userId, "front"))
                .map(e -> ServerSentEvent.<ToolInvocationEvent>builder().event(e.type()).data(e).build());
    }
    private ToolInvokeCommand toCommand(String toolCode, ToolInvokeRequest request, String tenantId, String userId, String channel) {
        // channel 用于审计区分前端直接调用、Agent 调用和 Relay Runtime 调用。
        return new ToolInvokeCommand(tenantId, userId, request.sessionId(), request.runId(), toolCode, request.idempotencyKey(), request.arguments(), request.confirmed(), channel, request.metadata());
    }
}
