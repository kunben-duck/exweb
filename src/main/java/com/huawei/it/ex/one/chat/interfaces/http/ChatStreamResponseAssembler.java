package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatEventDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ConversationTurnStreamDto;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public final class ChatStreamResponseAssembler {
    private final ChatEventTranslator eventTranslator;
    private final ChatTurnStreamTranslator turnStreamTranslator;
    private final ChatStreamProperties chatStreamProperties;

    public ChatStreamResponseAssembler(ChatEventTranslator eventTranslator,
                                       ChatTurnStreamTranslator turnStreamTranslator,
                                       ChatStreamProperties chatStreamProperties) {
        this.eventTranslator = eventTranslator;
        this.turnStreamTranslator = turnStreamTranslator;
        this.chatStreamProperties = chatStreamProperties;
    }

    public ChatEventDto toDto(ChatEvent event) {
        return eventTranslator.toDto(event);
    }

    public ConversationTurnStreamDto streamItem(ChatEventDto event) {
        return turnStreamTranslator.streamItem(event);
    }

    public ResponseEntity<Flux<ServerSentEvent<ConversationTurnStreamDto>>> sseResponse(
            Flux<ServerSentEvent<ConversationTurnStreamDto>> events) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(events);
    }

    public ServerSentEvent<ConversationTurnStreamDto> toTurnStreamSse(ConversationTurnStreamDto item) {
        return ServerSentEvent.<ConversationTurnStreamDto>builder()
                .event(ChatTurnStreamTranslator.TURN_STREAM_TYPE)
                .data(item)
                .build();
    }

    public Flux<ConversationTurnStreamDto> withTurnHeartbeatAndDone(Flux<ChatEventDto> events) {
        Duration interval = chatStreamProperties.normalizedTurnHeartbeatInterval();
        AtomicLong lastSeq = new AtomicLong(0);
        AtomicReference<String> lastSessionId = new AtomicReference<>();
        AtomicReference<String> lastRunId = new AtomicReference<>();
        AtomicReference<String> terminalType = new AtomicReference<>();
        Flux<ChatEventDto> trackedEvents = events.doOnNext(dto -> {
            lastSeq.set(dto.sequence());
            lastSessionId.set(dto.sessionId());
            lastRunId.set(dto.runId());
            if (turnStreamTranslator.isTerminal(dto)) {
                terminalType.set(dto.type());
            }
        });
        return trackedEvents.publish(shared -> {
            Flux<ConversationTurnStreamDto> streamItems = shared.map(turnStreamTranslator::streamItem);
            Flux<ConversationTurnStreamDto> heartbeat = turnHeartbeat(interval, lastSessionId, lastRunId, lastSeq)
                    .takeUntilOther(shared.ignoreElements());
            Flux<ConversationTurnStreamDto> merged = interval.isZero() || interval.isNegative()
                    ? streamItems
                    : Flux.merge(streamItems, heartbeat);
            return merged.concatWith(Mono.defer(() -> {
                String terminal = terminalType.get();
                String sessionId = lastSessionId.get();
                String runId = lastRunId.get();
                if (terminal == null || sessionId == null || runId == null) {
                    return Mono.empty();
                }
                return Mono.just(turnStreamTranslator.done(sessionId, runId, lastSeq.get(), terminal));
            }));
        });
    }

    private Flux<ConversationTurnStreamDto> turnHeartbeat(Duration interval,
                                                          AtomicReference<String> sessionId,
                                                          AtomicReference<String> runId,
                                                          AtomicLong lastSeq) {
        if (interval.isZero() || interval.isNegative()) {
            return Flux.empty();
        }
        return Flux.interval(interval)
                .filter(ignored -> sessionId.get() != null && runId.get() != null)
                .map(ignored -> turnStreamTranslator.heartbeat(sessionId.get(), runId.get(), lastSeq.get()));
    }
}
