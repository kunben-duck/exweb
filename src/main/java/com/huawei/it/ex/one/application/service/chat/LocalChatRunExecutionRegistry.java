package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 当前服务实例内正在执行的后台run及其owner-stop汇总上下文注册表。 */
@Component
public class LocalChatRunExecutionRegistry {
    private final Map<String, Entry> running = new ConcurrentHashMap<>();

    public void register(String runId, Disposable disposable) {
        register(runId, disposable, null);
    }

    public void register(String runId, Disposable disposable, RunExecutionClaim claim) {
        if (runId == null || runId.isBlank() || disposable == null) {
            return;
        }
        AtomicReference<Disposable> disposeNow = new AtomicReference<>();
        running.compute(runId, (ignored, current) -> {
            Entry entry = current == null ? new Entry() : current;
            synchronized (entry.monitor) {
                entry.disposable = disposable;
                if (claim != null) {
                    entry.claim = claim;
                }
                if (entry.ownerStopConfirmed()) {
                    disposeNow.set(disposable);
                }
            }
            return entry;
        });
        Disposable current = disposeNow.get();
        if (current != null) {
            current.dispose();
        }
    }

    public void registerClaim(RunExecutionClaim claim) {
        if (claim == null || claim.runId() == null || claim.runId().isBlank()) {
            return;
        }
        running.compute(claim.runId(), (ignored, current) -> {
            Entry entry = current == null ? new Entry() : current;
            synchronized (entry.monitor) {
                entry.claim = claim;
            }
            return entry;
        });
    }

    /** 在Runtime副作用前登记owner stop需要复用的当前run汇总上下文。 */
    void attachContext(RunEventPipelineContext context) {
        if (context == null || context.runId() == null || context.runId().isBlank()) {
            return;
        }
        running.compute(context.runId(), (ignored, current) -> {
            Entry entry = current == null ? new Entry() : current;
            synchronized (entry.monitor) {
                if (entry.claim == null || entry.claim.equals(context.executionClaim())) {
                    entry.claim = context.executionClaim();
                    entry.context = context;
                }
            }
            return entry;
        });
    }

    /** 尝试由当前claim占用本机run的用户stop汇总权。 */
    Optional<OwnerStopRegistration> beginOwnerStop(
            RunStopControlBus.Request request,
            Consumer<RunStopControlBus.Response> notifier) {
        if (request == null || notifier == null || request.runId() == null) {
            return Optional.empty();
        }
        AtomicReference<OwnerStopRegistration> result = new AtomicReference<>();
        running.computeIfPresent(request.runId(), (ignored, entry) -> {
            synchronized (entry.monitor) {
                if (entry.state != OwnerStopState.RUNNING
                        || entry.claim == null
                        || entry.context == null
                        || !request.ownerInstanceId().equals(entry.claim.ownerInstanceId())
                        || request.fencingToken() != entry.claim.fencingToken()) {
                    return entry;
                }
                entry.state = OwnerStopState.STOP_REQUESTED;
                entry.stopRequest = new OwnerStopRequest(request, notifier);
                result.set(new OwnerStopRegistration(entry.context, entry.claim, request.requestId()));
                return entry;
            }
        });
        return Optional.ofNullable(result.get());
    }

    /** 数据库已接收stop后确认本机可以取消执行订阅。 */
    boolean confirmOwnerStop(String runId, String requestId, ChatRun cancellingRun) {
        Entry entry = runId == null ? null : running.get(runId);
        if (entry == null) {
            return false;
        }
        synchronized (entry.monitor) {
            if ((entry.state != OwnerStopState.STOP_REQUESTED
                    && entry.state != OwnerStopState.FINALIZING)
                    || entry.stopRequest == null
                    || !entry.stopRequest.request().requestId().equals(requestId)) {
                return false;
            }
            entry.stopRequest.confirm(cancellingRun);
            return true;
        }
    }

    /** owner在数据库接收stop前失败时撤销内存占用，让当前pipeline继续受原状态机管理。 */
    void abortOwnerStop(String runId, String requestId) {
        Entry entry = runId == null ? null : running.get(runId);
        if (entry == null) {
            return;
        }
        synchronized (entry.monitor) {
            if ((entry.state == OwnerStopState.STOP_REQUESTED
                    || entry.state == OwnerStopState.FINALIZING)
                    && entry.stopRequest != null
                    && entry.stopRequest.request().requestId().equals(requestId)
                    && !entry.stopRequest.confirmed()) {
                entry.stopRequest.abort();
                if (entry.state == OwnerStopState.STOP_REQUESTED) {
                    entry.stopRequest = null;
                    entry.state = OwnerStopState.RUNNING;
                }
            }
        }
    }

    /** 取消已确认stop的本机后台订阅，但保留Entry直到owner终态提交结束。 */
    boolean disposeOwnerStop(String runId, String requestId) {
        Entry entry = runId == null ? null : running.get(runId);
        Disposable disposable;
        if (entry == null) {
            return false;
        }
        synchronized (entry.monitor) {
            if (!entry.ownerStopConfirmed()
                    || !entry.stopRequest.request().requestId().equals(requestId)) {
                return false;
            }
            disposable = entry.disposable;
        }
        if (disposable != null) {
            disposable.dispose();
            return true;
        }
        return false;
    }

    /** pipeline结束时移交owner stop终态汇总；普通结束则删除Entry。 */
    Optional<OwnerStopFinalization> finishPipeline(RunExecutionClaim claim) {
        if (claim == null || claim.runId() == null || claim.runId().isBlank()) {
            return Optional.empty();
        }
        AtomicReference<OwnerStopFinalization> result = new AtomicReference<>();
        running.computeIfPresent(claim.runId(), (ignored, entry) -> {
            synchronized (entry.monitor) {
                if (!claim.equals(entry.claim)) {
                    return entry;
                }
                if (entry.state == OwnerStopState.STOP_REQUESTED
                        && entry.stopRequest != null
                        && entry.context != null) {
                    /*
                     * Pipeline可能早于数据库owner-stop事务结束。先保留Assembly并启动异步finalizer，
                     * 再由确认信号决定是否提交，避免确认窗口删除Entry并丢失部分回答。
                     */
                    entry.state = OwnerStopState.FINALIZING;
                    result.set(new OwnerStopFinalization(
                            entry.context,
                            entry.claim,
                            entry.stopRequest.request(),
                            entry.stopRequest.notifier(),
                            entry.stopRequest.confirmation()));
                    return entry;
                }
                if (entry.state == OwnerStopState.FINALIZING) {
                    return entry;
                }
                return null;
            }
        });
        return Optional.ofNullable(result.get());
    }

    void completeOwnerStopFinalization(RunExecutionClaim claim) {
        if (claim == null || claim.runId() == null) {
            return;
        }
        running.computeIfPresent(claim.runId(), (ignored, entry) -> {
            synchronized (entry.monitor) {
                if (!claim.equals(entry.claim) || entry.state != OwnerStopState.FINALIZING) {
                    return entry;
                }
                entry.state = OwnerStopState.TERMINATED;
                entry.context = null;
                entry.stopRequest = null;
                entry.disposable = null;
                return null;
            }
        });
    }

    public boolean cancel(String runId) {
        Entry entry = running.remove(runId);
        if (entry == null) {
            return false;
        }
        Disposable disposable;
        synchronized (entry.monitor) {
            disposable = entry.disposable;
            entry.context = null;
            entry.stopRequest = null;
            entry.state = OwnerStopState.TERMINATED;
        }
        if (disposable == null) {
            return false;
        }
        disposable.dispose();
        return true;
    }

    public boolean cancel(RunExecutionClaim claim) {
        if (claim == null || claim.runId() == null || claim.runId().isBlank()) {
            return false;
        }
        AtomicReference<Disposable> cancelled = new AtomicReference<>();
        running.computeIfPresent(claim.runId(), (ignored, entry) -> {
            synchronized (entry.monitor) {
                if (!claim.equals(entry.claim) || entry.disposable == null) {
                    return entry;
                }
                cancelled.set(entry.disposable);
                if (entry.ownerStopConfirmed() || entry.state == OwnerStopState.FINALIZING) {
                    return entry;
                }
                entry.state = OwnerStopState.TERMINATED;
                entry.context = null;
                return null;
            }
        });
        Disposable disposable = cancelled.get();
        if (disposable == null) {
            return false;
        }
        disposable.dispose();
        return true;
    }

    public List<RunExecutionClaim> activeClaims() {
        return running.values().stream()
                .filter(Entry::heartbeatEligible)
                .map(Entry::claim)
                .filter(claim -> claim != null && claim.runId() != null)
                .toList();
    }

    public void complete(String runId) {
        if (runId != null) {
            running.remove(runId);
        }
    }

    public void complete(RunExecutionClaim claim) {
        if (claim == null || claim.runId() == null || claim.runId().isBlank()) {
            return;
        }
        running.computeIfPresent(claim.runId(), (ignored, entry) -> {
            synchronized (entry.monitor) {
                return claim.equals(entry.claim) ? null : entry;
            }
        });
    }

    record OwnerStopRegistration(
            RunEventPipelineContext context,
            RunExecutionClaim claim,
            String requestId
    ) {
    }

    record OwnerStopFinalization(
            RunEventPipelineContext context,
            RunExecutionClaim claim,
            RunStopControlBus.Request request,
            Consumer<RunStopControlBus.Response> notifier,
            Mono<ChatRun> cancellingRun
    ) {
    }

    private enum OwnerStopState {
        RUNNING,
        STOP_REQUESTED,
        FINALIZING,
        TERMINATED
    }

    private static final class Entry {
        private final Object monitor = new Object();
        private Disposable disposable;
        private RunExecutionClaim claim;
        private RunEventPipelineContext context;
        private OwnerStopState state = OwnerStopState.RUNNING;
        private OwnerStopRequest stopRequest;

        private boolean ownerStopConfirmed() {
            return (state == OwnerStopState.STOP_REQUESTED || state == OwnerStopState.FINALIZING)
                    && stopRequest != null
                    && stopRequest.confirmed();
        }

        private boolean heartbeatEligible() {
            synchronized (monitor) {
                return state == OwnerStopState.RUNNING;
            }
        }

        private RunExecutionClaim claim() {
            synchronized (monitor) {
                return claim;
            }
        }
    }

    private static final class OwnerStopRequest {
        private final RunStopControlBus.Request request;
        private final Consumer<RunStopControlBus.Response> notifier;
        private final Sinks.One<ChatRun> confirmation = Sinks.one();
        private ChatRun cancellingRun;

        private OwnerStopRequest(RunStopControlBus.Request request,
                                 Consumer<RunStopControlBus.Response> notifier) {
            this.request = request;
            this.notifier = notifier;
        }

        private RunStopControlBus.Request request() {
            return request;
        }

        private Consumer<RunStopControlBus.Response> notifier() {
            return notifier;
        }

        private Mono<ChatRun> confirmation() {
            return confirmation.asMono();
        }

        private void confirm(ChatRun run) {
            if (cancellingRun != null) {
                return;
            }
            cancellingRun = run;
            confirmation.tryEmitValue(run);
        }

        private void abort() {
            confirmation.tryEmitEmpty();
        }

        private boolean confirmed() {
            return cancellingRun != null;
        }
    }
}
