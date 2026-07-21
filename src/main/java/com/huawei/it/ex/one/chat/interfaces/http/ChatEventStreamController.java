package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.application.service.ChatEventStreamService;
import com.huawei.it.ex.one.chat.application.service.ChatRunQueryService;
import com.huawei.it.ex.one.chat.domain.ChatStreamStatus;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatEventDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatStreamStatusDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ConversationTurnStreamDto;
import com.huawei.it.ex.one.security.domain.UserContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP endpoints for event resume and current stream status. */
@RestController
@RequestMapping("/v1/chat")
public class ChatEventStreamController {
    private final ChatEventStreamService chatStreamService;
    private final ChatRunQueryService chatRunService;
    private final ChatRequestContextResolver requestContextResolver;
    private final ChatStreamResponseAssembler streamResponseAssembler;

    public ChatEventStreamController(
            ChatEventStreamService chatStreamService,
            ChatRunQueryService chatRunService,
            ChatRequestContextResolver requestContextResolver,
            ChatStreamResponseAssembler streamResponseAssembler) {
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.requestContextResolver = requestContextResolver;
        this.streamResponseAssembler = streamResponseAssembler;
    }

    @GetMapping(value = "/sessions/{sessionId}/events/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ConversationTurnStreamDto>>> resumeSessionEvents(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq) {
        UserContext user = requestContextResolver.resolveChatUser();
        Flux<ServerSentEvent<ConversationTurnStreamDto>> events =
                chatStreamService.resumeSession(user, sessionId, afterSeq)
                        .map(streamResponseAssembler::toDto)
                        .map(streamResponseAssembler::streamItem)
                        .map(streamResponseAssembler::toTurnStreamSse);
        return streamResponseAssembler.sseResponse(events);
    }

    @GetMapping(value = "/runs/{runId}/events/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ConversationTurnStreamDto>>> resumeRunEvents(
            @PathVariable("runId") String runId,
            @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq) {
        UserContext user = requestContextResolver.resolveChatUser();
        Flux<ChatEventDto> events = chatStreamService.resumeRun(user, runId, afterSeq)
                .map(streamResponseAssembler::toDto);
        return streamResponseAssembler.sseResponse(streamResponseAssembler.withTurnHeartbeatAndDone(events)
                .map(streamResponseAssembler::toTurnStreamSse));
    }

    @GetMapping(value = "/sessions/{sessionId}/stream-status")
    public Mono<ChatStreamStatusDto> streamStatus(@PathVariable("sessionId") String sessionId) {
        UserContext user = requestContextResolver.resolveChatUser();
        return Mono.fromCallable(() -> toStreamStatusDto(chatRunService.streamStatus(user, sessionId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ChatStreamStatusDto toStreamStatusDto(ChatStreamStatus status) {
        return new ChatStreamStatusDto(
                status.sessionId(),
                status.latestSeq(),
                status.activeRunId(),
                status.activeRunStatus() == null ? null : status.activeRunStatus().name(),
                status.activeStreamTopicId(),
                status.activeRunFirstSeq(),
                status.activeRunLastSeq(),
                status.cancellable(),
                status.waitingUserInput(),
                status.interactionId(),
                status.interactionType(),
                status.assistantMessageId(),
                status.expiresAt(),
                status.bindingProvider(),
                status.bindingTargetType(),
                status.bindingTargetId(),
                status.bindingIntentCode(),
                status.bindingIntentName(),
                status.bindingRouteSource(),
                status.bindingUpdatedAt()
        );
    }
}
