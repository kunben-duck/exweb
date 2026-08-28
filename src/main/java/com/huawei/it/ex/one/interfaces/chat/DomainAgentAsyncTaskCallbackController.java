package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.service.chat.DomainAgentAsyncTaskCallbackApplicationService;
import com.huawei.it.ex.one.application.service.chat.DomainAgentAsyncTaskCallbackCommand;
import com.huawei.it.ex.one.interfaces.chat.dto.DomainAgentAsyncTaskCallbackRequest;
import com.huawei.it.ex.one.interfaces.chat.dto.DomainAgentAsyncTaskCallbackResponse;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal callback endpoint protected by the enterprise gateway ACL. */
@RestController
@RequestMapping("/v1/internal/domain-agent/async-tasks")
@Validated
public class DomainAgentAsyncTaskCallbackController {
    private final DomainAgentAsyncTaskCallbackApplicationService service;

    public DomainAgentAsyncTaskCallbackController(
            DomainAgentAsyncTaskCallbackApplicationService service) {
        this.service = service;
    }

    @PostMapping("/callback")
    public Mono<DomainAgentAsyncTaskCallbackResponse> callback(
            @Valid @RequestBody DomainAgentAsyncTaskCallbackRequest request) {
        return service.callback(new DomainAgentAsyncTaskCallbackCommand(
                        request.runId(), request.status(), request.error()))
                .map(result -> new DomainAgentAsyncTaskCallbackResponse(result.accepted()));
    }
}
