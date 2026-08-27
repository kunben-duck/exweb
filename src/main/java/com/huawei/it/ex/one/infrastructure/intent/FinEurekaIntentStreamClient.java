package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamClient;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamFrame;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryContext;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryPolicy;
import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE implementation of the IntentDecision streaming client.
 *
 * <p>Each HTTP stream is one retry attempt. Process frames are emitted immediately, while a terminal
 * error is converted to the same degraded result used by the blocking client before consulting the
 * configured retry policy.</p>
 */
@Component
@EnableConfigurationProperties(IntentServiceHttpProperties.class)
public class FinEurekaIntentStreamClient implements IntentDecisionStreamClient {
    private static final AppLogger log = AppLoggerFactory.getLogger(FinEurekaIntentStreamClient.class);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final IntentServiceHttpProperties properties;
    private final IntentServiceWireMapper wireMapper;
    private final AuthHeaderProviderRegistry authHeaders;
    private final IntentRetryPolicy retryPolicy;
    private final Scheduler authIoScheduler;
    private final IntentPreferenceCorrectionLoader preferenceLoader;

    public FinEurekaIntentStreamClient(WebClient.Builder webClientBuilder,
                                      ObjectMapper objectMapper,
                                      IntentServiceHttpProperties properties,
                                      IntentServiceWireMapper wireMapper,
                                      AuthHeaderProviderRegistry authHeaders,
                                      IntentRetryPolicy retryPolicy,
                                      @Qualifier("intentStreamAuthScheduler") Scheduler authIoScheduler) {
        this(webClientBuilder, objectMapper, properties, wireMapper, authHeaders,
                retryPolicy, authIoScheduler, null);
    }

    @Autowired
    public FinEurekaIntentStreamClient(WebClient.Builder webClientBuilder,
                                      ObjectMapper objectMapper,
                                      IntentServiceHttpProperties properties,
                                      IntentServiceWireMapper wireMapper,
                                      AuthHeaderProviderRegistry authHeaders,
                                      IntentRetryPolicy retryPolicy,
                                      @Qualifier("intentStreamAuthScheduler") Scheduler authIoScheduler,
                                      IntentPreferenceCorrectionLoader preferenceLoader) {
        this.webClient = properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? webClientBuilder.build()
                : webClientBuilder.baseUrl(properties.getBaseUrl().trim()).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wireMapper = wireMapper;
        this.authHeaders = authHeaders;
        this.retryPolicy = retryPolicy;
        this.authIoScheduler = authIoScheduler;
        this.preferenceLoader = preferenceLoader;
    }

    @Override
    public Flux<IntentDecisionStreamFrame> recognize(ChatCommand command, MemoryContext memory, UserContext user) {
        return recognize(command, memory, user, null);
    }

    @Override
    public Flux<IntentDecisionStreamFrame> recognize(ChatCommand command,
                                                     MemoryContext memory,
                                                     UserContext user,
                                                     String userMessageId) {
        int maxAttempts = 1 + properties.normalizedMaxRetries();
        Mono<List<IntentUserPreferenceCorrection>> preferences = preferenceLoader == null
                || properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? Mono.just(List.of())
                : preferenceLoader.load(command, user);
        return preferences.flatMapMany(items -> executeAttempt(new StreamAttemptContext(
                command, memory, user, userMessageId, items, 1, maxAttempts)));
    }

    private Flux<IntentDecisionStreamFrame> executeAttempt(StreamAttemptContext context) {
        return Flux.defer(() -> streamOnce(context))
                .onErrorResume(failure -> recoverAttempt(context, failure));
    }

    private Flux<IntentDecisionStreamFrame> streamOnce(StreamAttemptContext context) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            return Flux.error(new IntentStreamProtocolException("IntentDecision base URL is not configured"));
        }
        return resolveAuthHeaders(context)
                .flatMapMany(headers -> requestStream(context, headers));
    }

    private Flux<IntentDecisionStreamFrame> requestStream(StreamAttemptContext context,
                                                          Map<String, String> resolvedAuthHeaders) {
        Flux<IntentDecisionStreamFrame> responseFrames = webClient.post()
                .uri(properties.getRecognizeStreamPath())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> resolvedAuthHeaders.forEach(headers::set))
                .bodyValue(wireMapper.toWireRequest(
                        context.command(), context.memory(), context.user(), context.userMessageId(),
                        context.preferenceCorrections()))
                .exchangeToFlux(response -> responseFrames(response, context));
        Flux<IntentDecisionStreamFrame> firstEventBound = responseFrames.timeout(
                Mono.delay(properties.normalizedStreamFirstEventTimeout()), ignored -> Mono.never());
        return withTotalTimeout(firstEventBound);
    }

    private Mono<Map<String, String>> resolveAuthHeaders(StreamAttemptContext context) {
        return Mono.fromCallable(() -> authHeaders.headers(authHeaderRequest(context.user())))
                .map(headers -> headers == null ? Map.<String, String>of() : Map.copyOf(headers))
                .subscribeOn(authIoScheduler)
                .timeout(properties.normalizedStreamAuthTimeout(),
                        Mono.error(new IntentStreamTimeoutException(
                                "IntentDecision stream authentication timeout")));
    }

    private Flux<IntentDecisionStreamFrame> responseFrames(ClientResponse response,
                                                           StreamAttemptContext context) {
        if (response.statusCode().isError()) {
            int statusCode = response.statusCode().value();
            return response.releaseBody()
                    .thenMany(Flux.error(new IntentStreamHttpException(statusCode)));
        }
        MediaType contentType = response.headers().contentType().orElse(null);
        if (contentType == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            return response.releaseBody().thenMany(Flux.error(
                    new IntentStreamProtocolException("IntentDecision stream response is not text/event-stream")));
        }

        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> networkFrames = response.bodyToFlux(SSE_TYPE)
                .timeout(properties.normalizedStreamIdleTimeout(),
                        Flux.error(new IntentStreamTimeoutException("IntentDecision stream idle timeout")));
        Flux<ServerSentEvent<String>> businessFrames = networkFrames
                .doOnNext(this::logUnknownEvent)
                .filter(this::knownBusinessEvent);
        Flux<IntentDecisionStreamFrame> mapped = businessFrames
                .concatMap(event -> mapBusinessEvent(event, context, terminalSeen))
                .concatWith(Mono.defer(() -> terminalSeen.get()
                        ? Mono.empty()
                        : Mono.error(new IntentStreamProtocolException(
                                "IntentDecision stream closed without result or error"))));
        return mapped.takeUntil(frame -> frame.type() == IntentDecisionStreamFrame.Type.RESULT);
    }

    private Flux<IntentDecisionStreamFrame> withTotalTimeout(Flux<IntentDecisionStreamFrame> response) {
        Flux<IntentDecisionStreamFrame> totalTimeout = Mono
                .delay(properties.normalizedStreamTotalTimeout())
                .then(Mono.<IntentDecisionStreamFrame>error(
                        new IntentStreamTimeoutException("IntentDecision stream total timeout")))
                .flux();
        return Flux.merge(response, totalTimeout)
                .takeUntil(frame -> frame.type() == IntentDecisionStreamFrame.Type.RESULT);
    }

    private Flux<IntentDecisionStreamFrame> mapBusinessEvent(ServerSentEvent<String> event,
                                                             StreamAttemptContext context,
                                                             AtomicBoolean terminalSeen) {
        String eventType = event.event();
        return switch (eventType) {
            case "progress" -> processFrame(event, context.attempt(), context.maxAttempts(), true)
                    .map(Flux::just)
                    .orElseGet(Flux::empty);
            case "delta" -> processFrame(event, context.attempt(), context.maxAttempts(), false)
                    .map(Flux::just)
                    .orElseGet(Flux::empty);
            case "result" -> {
                terminalSeen.set(true);
                yield resultFrame(event, context);
            }
            case "error" -> {
                terminalSeen.set(true);
                yield Flux.error(remoteError(event));
            }
            default -> Flux.empty();
        };
    }

    private Optional<IntentDecisionStreamFrame> processFrame(ServerSentEvent<String> event,
                                                             int attempt,
                                                             int maxAttempts,
                                                             boolean progress) {
        try {
            JsonNode data = parseData(event);
            if (data == null || !data.isObject()) {
                return Optional.empty();
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (progress) {
                putText(data, payload, "stage");
                putText(data, payload, "stageMessage");
                if (!payload.containsKey("stage")) {
                    return Optional.empty();
                }
                return Optional.of(IntentDecisionStreamFrame.progress(payload, attempt, maxAttempts));
            }
            JsonNode index = data.get("index");
            if (index != null && index.canConvertToLong()) {
                payload.put("index", index.longValue());
            }
            putText(data, payload, "content");
            if (!payload.containsKey("content")) {
                return Optional.empty();
            }
            return Optional.of(IntentDecisionStreamFrame.delta(payload, attempt, maxAttempts));
        } catch (JsonProcessingException | IntentStreamProtocolException ex) {
            log.warn("IntentDecision process event was ignored because its JSON is invalid. eventType={} attempt={}",
                    event.event(), attempt);
            return Optional.empty();
        }
    }

    private Flux<IntentDecisionStreamFrame> resultFrame(ServerSentEvent<String> event,
                                                        StreamAttemptContext context) {
        try {
            IntentRecognitionResult result = wireMapper.toRecognitionResult(parseData(event));
            if (result == null) {
                return Flux.error(new IntentStreamProtocolException(
                        "IntentDecision result event did not produce a recognition result"));
            }
            if (result.waitingClarification()) {
                return Flux.just(IntentDecisionStreamFrame.result(
                        result, context.attempt(), context.maxAttempts()));
            }
            if (result.status() == IntentRecognitionResult.Status.FINAL && result.decision() == null) {
                return Flux.error(new IntentStreamProtocolException(
                        "IntentDecision final result does not contain a decision"));
            }
            IntentRetryContext retryContext = new IntentRetryContext(
                    context.command(), context.memory(), context.user(),
                    result.decision(), context.attempt(), context.maxAttempts());
            if (shouldRetry(retryContext)) {
                return Flux.error(new RetryableIntentResultException(result));
            }
            if (result.status() == IntentRecognitionResult.Status.FAILED_OR_DEGRADED) {
                log.warn(SystemErrorLogEntry.builder(classifyIntentResult(result),
                                "IntentDecision stream returned a non-executable result")
                        .sessionId(context.command() == null ? null : context.command().sessionId())
                        .operation("intent.stream")
                        .attribute("attempt", context.attempt())
                        .attribute("maxAttempts", context.maxAttempts())
                        .build());
            }
            return Flux.just(IntentDecisionStreamFrame.result(
                    result, context.attempt(), context.maxAttempts()));
        } catch (JsonProcessingException ex) {
            return Flux.error(new IntentStreamProtocolException(
                    "IntentDecision result event contains invalid JSON"));
        }
    }

    private RuntimeException remoteError(ServerSentEvent<String> event) {
        try {
            JsonNode data = parseData(event);
            int statusCode = data != null && data.path("code").canConvertToInt()
                    ? data.path("code").intValue()
                    : 0;
            return new IntentStreamRemoteException(statusCode);
        } catch (JsonProcessingException ex) {
            return new IntentStreamProtocolException("IntentDecision error event contains invalid JSON");
        }
    }

    private Flux<IntentDecisionStreamFrame> recoverAttempt(StreamAttemptContext context,
                                                           Throwable failure) {
        Throwable cause = Exceptions.unwrap(failure);
        IntentRecognitionResult result = cause instanceof RetryableIntentResultException retryable
                ? retryable.result()
                : IntentRecognitionResult.degraded(wireMapper.degraded(safeFailureReason(cause)));
        boolean retry = cause instanceof RetryableIntentResultException
                || shouldRetry(new IntentRetryContext(
                        context.command(), context.memory(), context.user(), result.decision(),
                        context.attempt(), context.maxAttempts()));
        logAttemptFailure(context.command(), cause, context.attempt(), context.maxAttempts(), retry);
        if (retry && context.hasRemainingAttempts()) {
            return executeAttempt(context.nextAttempt());
        }
        return Flux.just(IntentDecisionStreamFrame.result(
                result, context.attempt(), context.maxAttempts()));
    }

    private boolean shouldRetry(IntentRetryContext context) {
        try {
            return retryPolicy.shouldRetry(context);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "IntentDecision retry policy failed; skipping remaining retries")
                    .sessionId(context.command() == null ? null : context.command().sessionId())
                    .operation("intent.stream.retry-policy")
                    .attribute("attempt", context.attempt())
                    .retryable(false)
                    .build(), ex);
            return false;
        }
    }

    private void logAttemptFailure(ChatCommand command,
                                   Throwable failure,
                                   int attempt,
                                   int maxAttempts,
                                   boolean retry) {
        log.warn(SystemErrorLogEntry.builder(classifyFailure(failure),
                        "IntentDecision stream attempt failed")
                .sessionId(command == null ? null : command.sessionId())
                .operation("intent.stream")
                .attribute("attempt", attempt)
                .attribute("maxAttempts", maxAttempts)
                .attribute("willRetry", retry && attempt < maxAttempts)
                .build(), failure);
    }

    private SystemErrorCode classifyFailure(Throwable failure) {
        if (failure instanceof RetryableIntentResultException retryable) {
            return classifyIntentResult(retryable.result());
        }
        if (failure instanceof IntentStreamTimeoutException || failure instanceof TimeoutException) {
            return SystemErrorCode.INTENT_DECISION_TIMEOUT;
        }
        if (failure instanceof IntentStreamRemoteException remote) {
            return classifyStatusCode(remote.statusCode());
        }
        if (failure instanceof IntentStreamHttpException response) {
            return classifyStatusCode(response.statusCode());
        }
        if (failure instanceof WebClientRequestException) {
            return SystemErrorCode.INTENT_DECISION_UNAVAILABLE;
        }
        if (failure instanceof IntentStreamProtocolException) {
            return SystemErrorCode.INTENT_DECISION_PROTOCOL_INVALID;
        }
        if (hasCause(failure, JsonProcessingException.class) || hasCause(failure, DecodingException.class)) {
            return SystemErrorCode.INTENT_DECISION_RESPONSE_PARSE_FAILED;
        }
        return SystemErrorCode.INTENT_DECISION_STREAM_FAILED;
    }

    private SystemErrorCode classifyStatusCode(int statusCode) {
        if (statusCode == 429) {
            return SystemErrorCode.INTENT_DECISION_RATE_LIMITED;
        }
        if (statusCode == 504) {
            return SystemErrorCode.INTENT_DECISION_TIMEOUT;
        }
        if (statusCode >= 500) {
            return SystemErrorCode.INTENT_DECISION_HTTP_SERVER_ERROR;
        }
        return statusCode >= 400
                ? SystemErrorCode.INTENT_DECISION_HTTP_CLIENT_ERROR
                : SystemErrorCode.INTENT_DECISION_STREAM_FAILED;
    }

    private SystemErrorCode classifyIntentResult(IntentRecognitionResult result) {
        if (result == null || result.decision() == null) {
            return SystemErrorCode.INTENT_DECISION_PROTOCOL_INVALID;
        }
        String reason = result.decision().raw() == null
                ? ""
                : String.valueOf(result.decision().raw().getOrDefault("reason", ""));
        if ("意图服务协议异常".equals(result.decision().intentName())
                || reason.contains("routeAction") || reason.contains("ROUTE_SINGLE")) {
            return SystemErrorCode.INTENT_DECISION_PROTOCOL_INVALID;
        }
        return SystemErrorCode.INTENT_DECISION_STATUS_FAILED;
    }

    private String safeFailureReason(Throwable failure) {
        if (failure instanceof IntentStreamRemoteException remote) {
            return "intent stream error code=" + remote.statusCode();
        }
        if (failure instanceof IntentStreamTimeoutException || failure instanceof TimeoutException) {
            return "intent stream timeout";
        }
        if (failure instanceof IntentStreamProtocolException) {
            return "intent stream protocol failure";
        }
        if (failure instanceof IntentStreamHttpException response) {
            return "intent stream HTTP status=" + response.statusCode();
        }
        return "intent stream request failed";
    }

    private boolean knownBusinessEvent(ServerSentEvent<String> event) {
        return event != null && event.event() != null && switch (event.event()) {
            case "progress", "delta", "result", "error" -> true;
            default -> false;
        };
    }

    private void logUnknownEvent(ServerSentEvent<String> event) {
        if (event != null && event.event() != null && !knownBusinessEvent(event)) {
            log.debug("IntentDecision stream ignored an unknown SSE event type");
        }
    }

    private JsonNode parseData(ServerSentEvent<String> event) throws JsonProcessingException {
        String data = event == null ? null : event.data();
        if (data == null || data.isBlank()) {
            throw new IntentStreamProtocolException("IntentDecision SSE event data is empty");
        }
        return objectMapper.readTree(data);
    }

    private void putText(JsonNode source, Map<String, Object> target, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.put(fieldName, value.asText());
        }
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> expectedType) {
        Throwable current = failure;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AuthHeaderRequest authHeaderRequest(UserContext user) {
        return new AuthHeaderRequest(
                user == null ? null : user.tenantId(),
                user == null ? null : user.ownerUserId(),
                "intent-service",
                "recognize-stream",
                properties.getBaseUrl(),
                properties.getRecognizeStreamPath(),
                null
        );
    }

    private record StreamAttemptContext(
            ChatCommand command,
            MemoryContext memory,
            UserContext user,
            String userMessageId,
            List<IntentUserPreferenceCorrection> preferenceCorrections,
            int attempt,
            int maxAttempts
    ) {
        private StreamAttemptContext {
            preferenceCorrections = preferenceCorrections == null
                    ? List.of()
                    : List.copyOf(preferenceCorrections);
        }

        private boolean hasRemainingAttempts() {
            return attempt < maxAttempts;
        }

        private StreamAttemptContext nextAttempt() {
            return new StreamAttemptContext(
                    command, memory, user, userMessageId, preferenceCorrections,
                    attempt + 1, maxAttempts);
        }
    }

    private static final class RetryableIntentResultException extends RuntimeException {
        private final IntentRecognitionResult result;

        private RetryableIntentResultException(IntentRecognitionResult result) {
            super("IntentDecision stream returned a retryable result");
            this.result = result;
        }

        private IntentRecognitionResult result() {
            return result;
        }
    }

    private static class IntentStreamProtocolException extends RuntimeException {
        private IntentStreamProtocolException(String message) {
            super(message);
        }
    }

    private static final class IntentStreamRemoteException extends RuntimeException {
        private final int statusCode;

        private IntentStreamRemoteException(int statusCode) {
            super("IntentDecision stream returned an error event");
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }

    private static final class IntentStreamHttpException extends RuntimeException {
        private final int statusCode;

        private IntentStreamHttpException(int statusCode) {
            super("IntentDecision stream returned an HTTP error");
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }

    private static final class IntentStreamTimeoutException extends RuntimeException {
        private IntentStreamTimeoutException(String message) {
            super(message);
        }
    }
}
