/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.observation.AgentObservationContext;
import com.huawei.it.ex.one.domain.observation.AgentObservationSnapshot;
import com.huawei.it.ex.one.domain.observation.TurnTraceContext;

import reactor.core.publisher.SignalType;

import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** 每次实际尝试独立采样，任何观测错误都不改变业务信号。 */
public final class AgentObservationRecorder {
    public static final AgentObservationRecorder DISABLED = new AgentObservationRecorder(
            TurnTraceCoordinator.DISABLED, null, null, null);
    private static final Scope NOOP = new Scope();
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\bbearer\\s+|\\b(?:authorization|set[-_]?cookie|cookie|api[-_]?key|"
                    + "(?:access[-_]?|refresh[-_]?)?token|password|secret)[\"\']?\\s*[:=]");
    private final TurnTraceCoordinator turns;
    private final ObservationWriter writer;
    private final FirstBusinessFrameClassifier classifier;
    private final ObservationDiagnostics diagnostics;
    private final Clock clock;
    private final LongSupplier nanos;

    public AgentObservationRecorder(TurnTraceCoordinator turns, ObservationWriter writer,
                                    FirstBusinessFrameClassifier classifier, ObservationDiagnostics diagnostics) {
        this(turns, writer, classifier, diagnostics, Clock.systemUTC(), System::nanoTime);
    }

    public AgentObservationRecorder(TurnTraceCoordinator turns, ObservationWriter writer,
                                    FirstBusinessFrameClassifier classifier, ObservationDiagnostics diagnostics,
                                    Clock clock, LongSupplier nanos) {
        this.turns = turns;
        this.writer = writer;
        this.classifier = classifier;
        this.diagnostics = diagnostics;
        this.clock = clock;
        this.nanos = nanos;
    }

    public Scope begin(TurnTraceContext context, Target target) { return begin(context, target, null); }

    public Scope begin(TurnTraceContext context, Target target, Scope previous) {
        if (!turns.enabled() || context == null) { return NOOP; }
        try {
            TurnTraceContext latest = turns.context(context.runId());
            if (latest != null) { context = latest; }
            AgentObservationContext call = new AgentObservationContext(context, TurnTraceCoordinator.id("obs"),
                    context.parentObservationId(), previous == null ? null : previous.id(),
                    previous == null || previous.context == null ? 1 : previous.context.attemptNo() + 1,
                    target.agentType(), target.agentMode(), target.operation(), target.skillId(), target.runtimeSessionId());
            Scope scope = new Scope(this, call, clock.instant(), nanos.getAsLong());
            writer.observation(scope.snapshot(null));
            turns.sample(context.runId(), false, call.observationId());
            return scope;
        } catch (RuntimeException ignored) { diagnostics.dropped("call_start"); return NOOP; }
    }

    public record Target(String agentType, String agentMode, String operation, String skillId, String runtimeSessionId) { }

    public static final class Scope {
        private final AgentObservationRecorder recorder;
        private final AgentObservationContext context;
        private final Instant started;
        private final long startedNanos;
        private Instant first;
        private Long latency;
        private String runtimeSessionId;
        private String outcome;
        private String rejectCode;
        private String rejectReason;
        private String rejectMessage;
        private Boolean recoverable;
        private boolean finished;

        private Scope() { recorder = null; context = null; started = null; startedNanos = 0; }
        private Scope(AgentObservationRecorder recorder, AgentObservationContext context, Instant started, long nanos) {
            this.recorder = recorder;
            this.context = context;
            this.started = started;
            this.startedNanos = nanos;
            this.runtimeSessionId = context.runtimeSessionId();
        }

        public String id() { return context == null ? null : context.observationId(); }

        public void headers(HttpHeaders headers) {
            if (context == null) { return; }
            safe(() -> {
                headers.set("X-Trace-Id", context.turn().traceId());
                headers.set("X-Observation-Id", context.observationId());
            });
        }

        public void event(ChatEvent event) {
            if (context == null) { return; }
            synchronized (this) {
                safe(() -> {
                    if (finished || event == null || event.payload() == null) { return; }
                    // 首帧策略可替换且失败开放，不能阻断独立的拒答/问卷结果识别。
                    safe(() -> {
                        var classification = recorder.classifier.classify(event);
                        if (classification.observation()) { firstFrame(); }
                        if (classification.turn()) { recorder.turns.sample(context.turn().runId(), false, null); }
                    });
                    Map<String, Object> payload = event.payload();
                    String session = text(payload.get("runtimeSessionId"));
                    if (session != null) { runtimeSessionId = session; }
                    semanticOutcome(event, payload);
                });
            }
        }

        private void semanticOutcome(ChatEvent event, Map<String, Object> payload) {
            if (isRefusal(payload)) {
                recordRefusal(payload);
            } else if (isQuestionnaire(payload)) {
                outcome = "WAITING_USER";
            } else if (isAsyncAccepted(event, payload)) {
                outcome = "ASYNC_ACCEPTED";
            } else {
                recordTerminalOutcome(event, payload);
            }
        }

        private boolean isRefusal(Map<String, Object> payload) {
            return "agent.refusal".equals(payload.get("sourceType")) || "agent.refusal".equals(payload.get("type"))
                    || "RELAY".equals(context.agentType()) && ("expert-rejection".equals(payload.get("sourceType"))
                    || "expert_rejection".equals(payload.get("sourceType")));
        }

        private boolean isQuestionnaire(Map<String, Object> payload) {
            return "approval-request".equals(payload.get("sourceType"))
                    && "questionnaire".equalsIgnoreCase(String.valueOf(payload.get("operation_type")));
        }

        private boolean isAsyncAccepted(ChatEvent event, Map<String, Object> payload) {
            return "run.async_running".equals(event.type()) || "agent.async_started".equals(payload.get("sourceType"));
        }

        private void recordRefusal(Map<String, Object> payload) {
            outcome = "REJECTED";
            rejectCode = TurnTraceCoordinator.boundedCode(text(payload.get("code")));
            rejectReason = TurnTraceCoordinator.boundedCode(text(payload.get("reasonCode")));
            recoverable = payload.get("recoverable") instanceof Boolean value ? value : null;
            TurnTraceContext latest = recorder.turns.context(context.turn().runId());
            boolean retain = latest == null ? context.turn().retainText() : latest.retainText();
            rejectMessage = retain ? sanitized(text(payload.get("reason"))) : null;
            if (retain && rejectMessage == null) { rejectMessage = sanitized(text(payload.get("userMessage"))); }
            recorder.turns.sample(context.turn().runId(), true, null);
        }

        private void recordTerminalOutcome(ChatEvent event, Map<String, Object> payload) {
            if ("RELAY".equals(context.agentType()) && ("session-state".equals(payload.get("sourceType"))
                    || "session_state".equals(payload.get("sourceType"))) && outcome == null) {
                outcome = switch (String.valueOf(payload.get("state"))) {
                    case "completed" -> "COMPLETED";
                    case "failed", "error" -> "ERROR";
                    case "paused", "cancelled" -> "CANCELLED";
                    default -> null;
                };
            } else if ("message.completed".equals(event.type()) && outcome == null) {
                outcome = "COMPLETED";
            } else if ("run.failed".equals(event.type()) && outcome == null) {
                outcome = "ERROR";
            }
        }

        /** Intent最终结果只计入该调用首帧，不将普通路由结果当作问答首帧。 */
        public void intentResult() {
            if (context == null) { return; }
            synchronized (this) {
                safe(() -> {
                    if (recorder.classifier.classifyIntentResult().observation()) { firstFrame(); }
                });
            }
        }

        private void firstFrame() {
            if (first == null) {
                first = recorder.clock.instant();
                latency = Math.max(0, (recorder.nanos.getAsLong() - startedNanos) / 1_000_000);
            }
        }

        public void outcome(String value) {
            if (context == null) { return; }
            synchronized (this) {
                safe(() -> {
                    if (!finished && (outcome == null || "ASYNC_CALLBACK".equals(context.operation()))) { outcome = value; }
                });
            }
        }

        public void error(Throwable error) {
            if (context == null) { return; }
            synchronized (this) {
                safe(() -> {
                    if (finished || outcome != null && !"COMPLETED".equals(outcome)) { return; }
                    outcome = "ERROR";
                    Throwable current = error;
                    for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
                        if (current instanceof java.util.concurrent.TimeoutException
                                || current.getClass().getSimpleName().contains("Timeout")) { outcome = "TIMEOUT"; break; }
                    }
                });
            }
        }

        public void finish(SignalType signal) {
            if (context == null) { return; }
            synchronized (this) {
                safe(() -> {
                    if (finished) { return; }
                    finished = true;
                    if (outcome == null) {
                        outcome = signal == SignalType.CANCEL ? "CANCELLED"
                                : signal == SignalType.ON_ERROR ? "ERROR" : "STREAM_ENDED";
                    }
                    AgentObservationSnapshot snapshot = snapshot(recorder.clock.instant());
                    recorder.writer.afterCommit(() -> recorder.writer.observation(snapshot));
                });
            }
        }

        private AgentObservationSnapshot snapshot(Instant end) {
            TurnTraceContext turn = context.turn();
            return new AgentObservationSnapshot(context.observationId(), turn.traceId(), turn.tenantId(), turn.userId(),
                    turn.sessionId(), turn.runId(), turn.userMessageId(), context.parentObservationId(),
                    context.retryOfObservationId(), context.attemptNo(), context.agentType(), context.agentMode(),
                    context.operation(), context.skillId(), runtimeSessionId, end == null ? "STARTED" : "FINISHED",
                    outcome, started, first, latency, end,
                    end == null ? null : Math.max(0, (recorder.nanos.getAsLong() - startedNanos) / 1_000_000),
                    rejectCode, rejectReason, rejectMessage, recoverable, started, end == null ? started : end);
        }

        private void safe(Runnable operation) {
            if (context == null) { return; }
            try { operation.run(); } catch (RuntimeException ignored) { recorder.diagnostics.dropped("call_sampling"); }
        }

        private static String text(Object value) { return value instanceof String text && !text.isBlank() ? text : null; }
        private static String sanitized(String value) {
            if (value == null) { return null; }
            // 下游拒答可能回显鉴权信息；检测到凭据标记时整段省略，避免Cookie多键或带空格值残留。
            String clean = SECRET.matcher(value).find() ? "[REDACTED]" : value;
            return clean.codePointCount(0, clean.length()) <= 1024 ? clean
                    : clean.substring(0, clean.offsetByCodePoints(0, 1024));
        }
    }
}
