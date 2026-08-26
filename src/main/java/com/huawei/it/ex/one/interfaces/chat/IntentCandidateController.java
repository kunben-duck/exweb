package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidate;
import com.huawei.it.ex.one.application.service.routing.IntentCandidateApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.dto.IntentCandidateDto;
import com.huawei.it.ex.one.interfaces.chat.dto.IntentCandidateQueryRequest;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Intent候选技能查询接口。 */
@RestController
@RequestMapping("/v1/chat")
@Validated
public class IntentCandidateController {
    private final IntentCandidateApplicationService candidateService;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;

    public IntentCandidateController(IntentCandidateApplicationService candidateService,
                                     AuthContextProvider auth,
                                     PermissionChecker permissionChecker) {
        this.candidateService = candidateService;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
    }

    @PostMapping("/intent-candidates")
    public Mono<List<IntentCandidateDto>> findCandidates(
            @Valid @RequestBody IntentCandidateQueryRequest request) {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return candidateService.findCandidates(user, request.messageId())
                .map(candidates -> candidates.stream().map(this::toDto).toList());
    }

    private IntentCandidateDto toDto(IntentCandidate candidate) {
        return new IntentCandidateDto(
                candidate.intentId(),
                candidate.accessName(),
                candidate.skillId(),
                candidate.intentName(),
                candidate.confidence());
    }
}
