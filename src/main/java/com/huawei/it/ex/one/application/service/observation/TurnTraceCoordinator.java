/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.observation.ObservationMetadata;
import com.huawei.it.ex.one.domain.observation.TurnTraceContext;
import com.huawei.it.ex.one.domain.observation.TurnTraceSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 本机采样状态仅随执行存活；跨实例恢复只读取业务记录中的可信私有快照。 */
public final class TurnTraceCoordinator {
    public static final TurnTraceCoordinator DISABLED = new TurnTraceCoordinator(false, null, null, null);
    private final boolean enabled;
    private final ObservationWriter writer;
    private final FirstBusinessFrameClassifier classifier;
    private final ObservationDiagnostics diagnostics;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, ContinuationStart> pendingContinuations = new ConcurrentHashMap<>();
    private final Clock clock;
    private final LongSupplier nanos;
    private final String clockInstanceId;

    public TurnTraceCoordinator(boolean enabled, ObservationWriter writer,
                                FirstBusinessFrameClassifier classifier, ObservationDiagnostics diagnostics) {
        this(enabled, writer, classifier, diagnostics, Clock.systemUTC(), System::nanoTime);
    }

    public TurnTraceCoordinator(boolean enabled, ObservationWriter writer,
                                FirstBusinessFrameClassifier classifier, ObservationDiagnostics diagnostics,
                                Clock clock, LongSupplier nanos) {
        this.enabled = enabled;
        this.writer = writer;
        this.classifier = classifier;
        this.diagnostics = diagnostics;
        this.clock = clock;
        this.nanos = nanos;
        this.clockInstanceId = enabled ? UUID.randomUUID().toString() : null;
    }

    public boolean enabled() { return enabled; }

    public void prepareContinuation(String runId) {
        safely(() -> pendingContinuations.putIfAbsent(runId,
                new ContinuationStart(clock.instant(), nanos.getAsLong())));
    }

    public void begin(UserContext user, String runId, String origin, String sessionId) {
        safely(() -> {
            ContinuationStart pending = closeContinuation(runId);
            Instant now = pending == null ? clock.instant() : pending.at;
            long startedNanos = pending == null ? nanos.getAsLong() : pending.nanos;
            TurnTraceContext context = new TurnTraceContext(id("turn"), user.tenantId(), user.ownerUserId(),
                    sessionId, runId, null, null, null, origin, runId, null, 0, now,
                    null, null, false, null, true, clockInstanceId, startedNanos);
            states.put(runId, new State(context, true, false));
            writer.trace(snapshot(context, "STARTED", 0, null, null));
        });
    }

    /** 仅消费尚未结束的启动记录；同步认领迟到返回不能重新创建执行持有关系。 */
    public void inherit(ChatInteractionRequest interaction, String runId) {
        safely(() -> {
            ContinuationStart pending = pendingContinuations.get(runId);
            if (pending == null) { return; }
            TurnTraceContext original = ObservationMetadata.read(interaction.requestPayload(),
                    interaction.tenantId(), interaction.userId());
            TurnTraceContext next = continuationContext(interaction, runId, original, pending);
            synchronized (pending) {
                if (pending.closed || pendingContinuations.get(runId) != pending) { return; }
                // 先登记再消费：finishStart要么关闭该记录，要么能看到已登记的执行状态。
                states.put(runId, new State(next, true, original != null));
                pending.closed = true;
                pendingContinuations.remove(runId, pending);
            }
            if (original == null) {
                writer.trace(snapshot(next, "STARTED", 0, null, null));
            }
        });
    }

    private TurnTraceContext continuationContext(ChatInteractionRequest interaction, String runId,
                                                 TurnTraceContext original, ContinuationStart pending) {
        if (original == null) {
            return new TurnTraceContext(id("turn"), interaction.tenantId(), interaction.userId(),
                    interaction.sessionId(), runId, null, interaction.sourceRunId(), null,
                    "LEGACY_CONTINUATION", runId, null, 0, pending.at,
                    null, null, false, null, true, clockInstanceId, pending.nanos);
        }
        return new TurnTraceContext(original.traceId(), original.tenantId(), original.userId(),
                interaction.sessionId(), original.rootRunId(), original.rootUserMessageId(),
                original.sourceRunId(), original.sourceTraceId(), original.originType(), runId,
                interaction.userMessageId(), original.runOrdinal() + 1, original.startedAt(),
                original.firstBusinessFrameAt(), original.firstBusinessFrameLatencyMs(), original.refusal(),
                original.parentObservationId(), original.retainText(), original.clockInstanceId(), original.startedNanos());
    }

    private ContinuationStart closeContinuation(String runId) {
        ContinuationStart pending = pendingContinuations.get(runId);
        if (pending != null) {
            synchronized (pending) {
                pending.closed = true;
                pendingContinuations.remove(runId, pending);
            }
        }
        return pending;
    }

    public void source(String runId, String sourceRunId, TurnTraceContext source, String origin) {
        safely(() -> {
            State state = states.get(runId);
            if (state == null) { return; }
            synchronized (state) {
                TurnTraceContext c = state.context;
                state.context = new TurnTraceContext(c.traceId(), c.tenantId(), c.userId(), c.sessionId(),
                        c.rootRunId(), c.rootUserMessageId(), sourceRunId, source == null ? null : source.traceId(),
                        origin == null ? c.originType() : origin, c.runId(), c.userMessageId(), c.runOrdinal(),
                        c.startedAt(), c.firstBusinessFrameAt(), c.firstBusinessFrameLatencyMs(), c.refusal(),
                        c.parentObservationId(), c.retainText(), c.clockInstanceId(), c.startedNanos());
            }
        });
    }

    public TurnTraceContext context(String runId) {
        if (!enabled || runId == null) { return null; }
        State state = states.get(runId);
        if (state == null) { return null; }
        synchronized (state) { return state.context; }
    }

    /** 只读取可信快照，不隐式登记本机状态；需要继续采样的操作必须显式持有Scope。 */
    public TurnTraceContext restore(ChatRun run, boolean legacyStop) {
        if (!enabled || run == null) { return null; }
        try {
            TurnTraceContext context = context(run.id());
            if (context == null) {
                context = ObservationMetadata.read(run.metadata(), run.tenantId(), run.userId());
                if (context == null && legacyStop) {
                    context = legacyStopContext(run);
                    writer.trace(snapshot(context, "STARTED", 0, null, null));
                }
            }
            return context;
        } catch (RuntimeException ignored) { diagnostics.dropped("restore"); return null; }
    }

    /** Stop/回调显式持有采样状态；同Run并发操作各自关闭，不能相互提前清理。 */
    public Scope openScope(ChatRun run, boolean legacyStop) {
        if (!enabled || run == null) { return Scope.EMPTY; }
        Scope scope = Scope.EMPTY;
        try {
            TurnTraceContext context = restore(run, false);
            boolean legacy = context == null && legacyStop;
            if (legacy) { context = legacyStopContext(run); }
            if (context == null) { return Scope.EMPTY; }
            scope = acquire(context);
            if (legacy) {
                boolean created;
                synchronized (scope.state) {
                    created = scope.state.context.traceId().equals(context.traceId());
                }
                if (created) { writer.trace(snapshot(context, "STARTED", 0, null, null)); }
            }
            return scope;
        } catch (RuntimeException ignored) {
            scope.close();
            diagnostics.dropped("restore");
            return Scope.EMPTY;
        }
    }

    private TurnTraceContext legacyStopContext(ChatRun run) {
        return new TurnTraceContext(id("turn"), run.tenantId(), run.userId(), run.sessionId(),
                run.id(), null, run.id(), null, "LEGACY_STOP", run.id(), null, 0,
                clock.instant(), null, null, false, null, true, clockInstanceId, nanos.getAsLong())
                .admitted(run.sessionId(), run.userMessageId());
    }

    private Scope acquire(TurnTraceContext context) {
        String runId = context.runId();
        for (;;) {
            State state = states.get(runId);
            if (state == null) {
                State restored = new State(context, false, true);
                restored.holders = 1;
                state = states.putIfAbsent(runId, restored);
                if (state == null) { return new Scope(this, runId, restored); }
            }
            synchronized (state) {
                // 最后一次释放可能刚移除该实例，不能在已脱离Map的状态上增加持有者。
                if (states.get(runId) != state) { continue; }
                state.holders++;
                return new Scope(this, runId, state);
            }
        }
    }

    /** 合并原有INSERT/UPDATE，不另发SQL；失败时保留业务记录原值。 */
    public ChatRun decorate(ChatRun run) {
        if (!enabled || run == null) { return run; }
        try {
            State state = states.get(run.id());
            if (state == null) {
                TurnTraceContext context = restore(run, false);
                return context == null ? run : decorate(run, context);
            }
            synchronized (state) {
                state.context = admittedContext(run, state.context);
                return run.withMetadataSnapshot(ObservationMetadata.with(run.metadata(), state.context));
            }
        } catch (RuntimeException ignored) { diagnostics.dropped("context"); return run; }
    }

    private ChatRun decorate(ChatRun run, TurnTraceContext context) {
        return run.withMetadataSnapshot(ObservationMetadata.with(run.metadata(), admittedContext(run, context)));
    }

    private TurnTraceContext admittedContext(ChatRun run, TurnTraceContext context) {
        TurnTraceContext admitted = context.admitted(run.sessionId(), run.userMessageId());
        var policy = AgentDataPersistenceMetadata.readRunPolicy(run.metadata());
        return policy == null ? admitted
                : admitted.withRetention(policy.policy() == AgentDataPersistencePolicy.FULL);
    }

    public Map<String, Object> interactionMetadata(String runId, Map<String, Object> metadata) {
        if (!enabled) { return metadata; }
        try {
            TurnTraceContext context = context(runId);
            return context == null ? metadata : ObservationMetadata.with(metadata, context);
        } catch (RuntimeException ignored) { diagnostics.dropped("context"); return metadata; }
    }

    public void committed(ChatRun run, String errorCode) {
        safely(() -> {
            TurnTraceContext context = restore(run, false);
            if (context == null) { return; }
            String status = run.status().name();
            if ("RUNNING".equals(status) && run.metadata().containsKey("_domainAgentAsyncTask")) {
                status = "ASYNC_WAITING";
            }
            if ("CANCELLING".equals(status)) { return; }
            boolean terminal = "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
            int version = terminal ? 3 : "WAITING_USER".equals(status) || "ASYNC_WAITING".equals(status) ? 2 : 1;
            TurnTraceSnapshot snapshot = snapshot(context, status, version, terminal ? clock.instant() : null,
                    "FAILED".equals(status) ? boundedCode(errorCode) : null);
            State state = states.get(run.id());
            writer.afterCommit(() -> {
                if (state != null) {
                    synchronized (state) {
                        if (state.committedVersion >= version) { return; }
                        state.admitted = true;
                        state.committedVersion = Math.max(state.committedVersion, version);
                    }
                }
                writer.trace(snapshot);
            });
        });
    }

    public void event(ChatEvent event) {
        safely(() -> {
            if (event == null) { return; }
            TurnTraceContext context = context(event.runId());
            if (context != null && context.firstBusinessFrameAt() == null && classifier.classify(event).turn()) {
                sample(event.runId(), false, null);
            }
        });
    }

    public void sample(String runId, boolean refusal, String parentObservationId) {
        safely(() -> {
            State state = states.get(runId);
            if (state == null) { return; }
            synchronized (state) {
                Instant first = refusal || parentObservationId != null || state.context.firstBusinessFrameAt() != null
                        ? null : clock.instant();
                state.context = state.context.sampled(first, first == null ? null : elapsed(state, first),
                        refusal, parentObservationId);
            }
        });
    }

    public void finishStart(String runId) {
        safely(() -> {
            closeContinuation(runId);
            State state = states.get(runId);
            if (state == null) { return; }
            synchronized (state) {
                if (!state.executing) { return; }
                try {
                    if (!state.admitted && !state.continuation) {
                        // 未提交准入只能结束自己的预备Trace，不能抢占原问答的等待阶段。
                        writer.trace(snapshot(state.context, "FAILED", 3, clock.instant(), null).beforeAdmission());
                    }
                } finally {
                    // 远端终态和失权不会推进本机版本；释放不能依赖审计或业务终态提交成功。
                    state.executing = false;
                    removeIfUnused(runId, state);
                }
            }
        });
    }

    public void waitingStopped(ChatRun run) {
        safely(() -> {
            TurnTraceContext context = restore(run, true);
            if (context == null) { return; }
            TurnTraceSnapshot snapshot = snapshot(context, "CANCELLED", 3, clock.instant(), null);
            writer.afterCommit(() -> writer.trace(snapshot));
        });
    }

    public void forget(String runId) {
        if (!enabled || runId == null) { return; }
        State state = states.get(runId);
        if (state != null) {
            synchronized (state) {
                removeIfUnused(runId, state);
            }
        }
    }

    /** 调用方持有state监视器；旧作用域不能移除同Run后来登记的实例。 */
    private void removeIfUnused(String runId, State state) {
        if (!state.executing && state.holders == 0) { states.remove(runId, state); }
    }

    private TurnTraceSnapshot snapshot(TurnTraceContext c, String status, int version, Instant end, String code) {
        Long elapsed = end == null ? null : elapsed(c, end);
        return new TurnTraceSnapshot(c.traceId(), c.tenantId(), c.userId(), c.sessionId(), c.rootRunId(),
                c.rootUserMessageId(), c.sourceRunId(), c.sourceTraceId(), c.runId(), c.originType(),
                c.runOrdinal(), version, status, c.refusal(), c.startedAt(), c.firstBusinessFrameAt(),
                c.firstBusinessFrameLatencyMs(), end, elapsed, code, c.startedAt(), clock.instant());
    }

    private long elapsed(State state, Instant end) {
        return elapsed(state.context, end);
    }

    private long elapsed(TurnTraceContext context, Instant end) {
        return context.startedNanos() == null || !clockInstanceId.equals(context.clockInstanceId())
                ? milliseconds(context.startedAt(), end)
                : Math.max(0, (nanos.getAsLong() - context.startedNanos()) / 1_000_000);
    }

    static long milliseconds(Instant start, Instant end) { return Math.max(0, Duration.between(start, end).toMillis()); }
    static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
    static String boundedCode(String value) {
        return value == null || value.length() > 128 ? null : value;
    }

    private void safely(Runnable task) {
        if (!enabled) { return; }
        try { task.run(); } catch (RuntimeException ignored) { diagnostics.dropped("sampling"); }
    }

    private static final class ContinuationStart {
        private final Instant at;
        private final long nanos;
        private boolean closed;

        private ContinuationStart(Instant at, long nanos) {
            this.at = at;
            this.nanos = nanos;
        }
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope EMPTY = new Scope(null, null, null);
        private final TurnTraceCoordinator owner;
        private final String runId;
        private final State state;
        private boolean closed;

        private Scope(TurnTraceCoordinator owner, String runId, State state) {
            this.owner = owner;
            this.runId = runId;
            this.state = state;
        }

        /** 同步任务实际开始时另行持有，外层订阅取消不能提前释放正在提交的操作。 */
        public Scope retain() {
            if (owner == null) { return EMPTY; }
            try {
                TurnTraceContext context;
                synchronized (state) { context = state.context; }
                // 取消与Callable启动存在竞争；仅实际启动的任务可从已捕获快照建立短作用域。
                return owner.acquire(context);
            } catch (RuntimeException ignored) {
                owner.diagnostics.dropped("restore");
                return EMPTY;
            }
        }

        @Override public void close() {
            if (owner == null) { return; }
            synchronized (state) {
                if (closed) { return; }
                closed = true;
                state.holders--;
                owner.removeIfUnused(runId, state);
            }
        }
    }

    private static final class State {
        private TurnTraceContext context;
        private boolean executing;
        private final boolean continuation;
        private boolean admitted;
        private int committedVersion;
        private int holders;
        private State(TurnTraceContext context, boolean executing, boolean continuation) {
            this.context = context;
            this.executing = executing;
            this.continuation = continuation;
            this.admitted = !executing;
        }
    }
}
