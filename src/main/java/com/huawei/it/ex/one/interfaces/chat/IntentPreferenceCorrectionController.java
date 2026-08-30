/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.service.routing.IntentPreferenceCorrectionApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentPreferenceCorrectionCommand;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatSelectedIntentDto;
import com.huawei.it.ex.one.interfaces.chat.dto.IntentPreferenceCorrectionRequest;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Independent endpoint for recording user-selected Intent preferences. */
@RestController
@RequestMapping("/v1/chat")
@Validated
public class IntentPreferenceCorrectionController {
    private final IntentPreferenceCorrectionApplicationService service;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;

    public IntentPreferenceCorrectionController(
            IntentPreferenceCorrectionApplicationService service,
            AuthContextProvider auth,
            PermissionChecker permissionChecker) {
        this.service = service;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
    }

    @PostMapping("/intent-preference-corrections")
    public Mono<ResponseEntity<Void>> record(
            @Valid @RequestBody IntentPreferenceCorrectionRequest request) {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        ChatSelectedIntentDto selected = request.selectedIntent();
        IntentPreferenceCorrectionCommand command = new IntentPreferenceCorrectionCommand(
                request.selectionType(),
                request.sourceMessageId(),
                selected == null ? null : new IntentPreferenceCorrectionCommand.SelectedIntent(
                        selected.intentId(), selected.intentName()),
                request.interactionId(),
                request.intentAccessName());
        return service.record(user, command).thenReturn(ResponseEntity.noContent().build());
    }
}
