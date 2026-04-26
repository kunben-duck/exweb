package com.huawei.finance.front.one.interfaces.internal;

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

/**
 * 面向 Relay Agent / Runtime 的内部工具调用接口。
 *
 * <p>外部 Runtime 不直接访问第三方工具，而是通过该接口回到统一工具网关。</p>
 */
@RestController
@RequestMapping("/internal/v1/finance/tools")
public class InternalToolInvocationController {
    private final ToolInvokeFacade facade;
    public InternalToolInvocationController(ToolInvokeFacade facade) { this.facade = facade; }
    @PostMapping(value = "/{toolCode}/invoke", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<ToolInvocationEvent> invoke(@PathVariable String toolCode, @RequestBody ToolInvokeRequest request,
                                            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                            @RequestHeader(value = "X-User-Id", defaultValue = "runtime") String userId) {
        // 内部调用默认 channel 为 relay-agent，方便工具审计区分来源。
        return facade.invoke(new ToolInvokeCommand(tenantId, userId, request.sessionId(), request.runId(), toolCode, request.idempotencyKey(), request.arguments(), request.confirmed(), "relay-agent", request.metadata()));
    }
    @PostMapping(value = "/{toolCode}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ToolInvocationEvent>> stream(@PathVariable String toolCode, @RequestBody ToolInvokeRequest request,
                                                             @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                             @RequestHeader(value = "X-User-Id", defaultValue = "runtime") String userId) {
        return invoke(toolCode, request, tenantId, userId).map(e -> ServerSentEvent.<ToolInvocationEvent>builder().event(e.type()).data(e).build());
    }
}
