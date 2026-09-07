/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.service.routing.IntentFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.dto.IntentFeedbackDto;
import com.huawei.it.ex.one.interfaces.chat.dto.IntentFeedbackRequest;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Feedback is independent from run execution, candidate switching and message likes. */
@RestController
@RequestMapping("/v1/chat/runs/{runId}/intent-feedback")
public class IntentFeedbackController {
    private final IntentFeedbackApplicationService service;
    private final AuthContextProvider auth;
    private final PermissionChecker permissions;

    public IntentFeedbackController(IntentFeedbackApplicationService service,
            AuthContextProvider auth, PermissionChecker permissions) {
        this.service = service;
        this.auth = auth;
        this.permissions = permissions;
    }

    @PostMapping
    public Mono<IntentFeedbackDto> record(@PathVariable("runId") String runId,
            @Valid @RequestBody IntentFeedbackRequest request) {
        UserContext user = user();
        return service.record(user, runId, request.toCommand()).map(IntentFeedbackDto::from);
    }

    @GetMapping
    public Mono<ResponseEntity<IntentFeedbackDto>> find(@PathVariable("runId") String runId) {
        return service.find(user(), runId).map(value -> value
                .map(feedback -> ResponseEntity.ok(IntentFeedbackDto.from(feedback)))
                .orElseGet(() -> ResponseEntity.noContent().build()));
    }

    private UserContext user() {
        UserContext user = auth.resolve();
        permissions.checkChatPermission(user);
        return user;
    }
}
