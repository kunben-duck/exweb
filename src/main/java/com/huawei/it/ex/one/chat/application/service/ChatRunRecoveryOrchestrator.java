package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.chat.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.chat.application.repository.ChatRunExecutionRepository;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRecoverLock;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.common.instance.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.chat.application.recovery.RuntimeTakeoverRecoveryStrategy;
import com.huawei.it.ex.one.chat.application.recovery.StaleRunRecoveryContext;
import com.huawei.it.ex.one.chat.application.recovery.StaleRunRecoveryResult;
import com.huawei.it.ex.one.chat.application.recovery.StaleRunRecoveryStrategy;
import com.huawei.it.ex.one.chat.application.recovery.StaleRunRecoveryStrategyRegistry;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunExecution;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * stale run 恢复编排服务。
 *
 * <p>该服务负责拉取过期 execution、执行本机容量治理、尝试 Redis 优化锁、通过数据库条件更新
 * 抢占恢复权，并按配置策略链调用具体恢复策略。它不承担定时调度，调度由 watchdog scheduler 完成。</p>
 */
@Service
public class ChatRunRecoveryOrchestrator {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunRecoveryOrchestrator.class);

    private final ChatRunExecutionRepository executionRepository;
    private final ChatRunRepository runRepository;
    private final ChatRunRecoverLock recoverLock;
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final ChatRunOperationalProperties properties;
    private final ChatRunRecoveryCapacityLimiter capacityLimiter;
    private final StaleRunRecoveryStrategyRegistry strategyRegistry;
    private final ChatRunRecoveryReconciliationService reconciliationService;

    @Autowired
    public ChatRunRecoveryOrchestrator(ChatRunExecutionRepository executionRepository,
                                       ChatRunRepository runRepository,
                                       ChatRunRecoverLock recoverLock,
                                       ApplicationInstanceIdProvider instanceIdProvider,
                                       ChatRunOperationalProperties properties,
                                       ChatRunRecoveryCapacityLimiter capacityLimiter,
                                       StaleRunRecoveryStrategyRegistry strategyRegistry,
                                       ChatRunRecoveryReconciliationService reconciliationService) {
        this.executionRepository = executionRepository;
        this.runRepository = runRepository;
        this.recoverLock = recoverLock;
        this.instanceIdProvider = instanceIdProvider;
        this.properties = properties;
        this.capacityLimiter = capacityLimiter;
        this.strategyRegistry = strategyRegistry;
        this.reconciliationService = reconciliationService;
    }

    public ChatRunRecoveryOrchestrator(ChatRunExecutionRepository executionRepository,
                                       ChatRunRepository runRepository,
                                       ChatRunRecoverLock recoverLock,
                                       ApplicationInstanceIdProvider instanceIdProvider,
                                       ChatRunOperationalProperties properties,
                                       ChatRunRecoveryCapacityLimiter capacityLimiter,
                                       StaleRunRecoveryStrategyRegistry strategyRegistry,
                                       ChatInteractionApplicationService interactionService,
                                       ChatRunTerminalCommitService terminalCommitService,
                                       ChatStreamApplicationService streamService,
                                       ChatRunApplicationService runService) {
        this(executionRepository, runRepository, recoverLock, instanceIdProvider, properties,
                capacityLimiter, strategyRegistry,
                new ChatRunRecoveryReconciliationService(
                        executionRepository, runRepository, properties, interactionService,
                        terminalCommitService, streamService, runService));
    }

    public ChatRunRecoveryOrchestrator(ChatRunExecutionRepository executionRepository,
                                       ChatRunRepository runRepository,
                                       ChatRunRecoverLock recoverLock,
                                       ApplicationInstanceIdProvider instanceIdProvider,
                                       ChatRunOperationalProperties properties,
                                       ChatRunRecoveryCapacityLimiter capacityLimiter,
                                       StaleRunRecoveryStrategyRegistry strategyRegistry,
                                       ChatInteractionApplicationService interactionService) {
        this(executionRepository, runRepository, recoverLock, instanceIdProvider, properties,
                capacityLimiter, strategyRegistry,
                new ChatRunRecoveryReconciliationService(
                        executionRepository, runRepository, properties, interactionService,
                        null, null, null));
    }

    public ChatRunRecoveryOrchestrator(ChatRunExecutionRepository executionRepository,
                                       ChatRunRepository runRepository,
                                       ChatRunRecoverLock recoverLock,
                                       ApplicationInstanceIdProvider instanceIdProvider,
                                       ChatRunOperationalProperties properties,
                                       ChatRunRecoveryCapacityLimiter capacityLimiter,
                                       StaleRunRecoveryStrategyRegistry strategyRegistry) {
        this(executionRepository, runRepository, recoverLock, instanceIdProvider, properties,
                capacityLimiter, strategyRegistry,
                new ChatRunRecoveryReconciliationService(
                        executionRepository, runRepository, properties, null, null, null, null));
    }

    /**
     * 扫描并恢复一批过期 run。
     *
     * @return 本轮成功恢复的 run 数量。
     */
    public int recoverExpiredRuns() {
        int batchSize = properties.normalizedWatchdogBatchSize();
        reconciliationService.reconcileTerminalInteractionClaims(batchSize);
        int maxClaims = properties.normalizedWatchdogMaxClaimsPerScan();
        int recovered = reconciliationService.reconcileRunExecutionInitOrphans(Math.min(batchSize, maxClaims));
        int remainingClaims = Math.max(0, maxClaims - recovered);
        if (remainingClaims == 0) {
            return recovered;
        }
        List<ChatRunExecution> candidates = new ArrayList<>(executionRepository.findLeaseExpired(batchSize));
        candidates.addAll(executionRepository.findRecoveryExpired(Math.max(1, batchSize - candidates.size())));
        return recovered + recoverCandidates(candidates, remainingClaims);
    }

    /**
     * 对指定 run 执行一次轻量懒收敛。
     *
     * @param runId run 标识。
     * @return true 表示本次已成功恢复或已无须恢复。
     */
    public boolean recoverExpiredRun(String runId) {
        Optional<ChatRunExecution> execution = executionRepository.findByRunId(runId);
        if (execution.isEmpty() || !executionRepository.isLeaseExpired(runId, java.time.Instant.now())) {
            return true;
        }
        return recoverCandidates(List.of(execution.get()), 1) > 0;
    }

    private int recoverCandidates(List<ChatRunExecution> candidates, int maxClaims) {
        if (candidates == null || candidates.isEmpty() || maxClaims <= 0) {
            return 0;
        }
        Map<String, Integer> tenantClaims = new LinkedHashMap<>();
        int recovered = 0;
        for (ChatRunExecution execution : candidates) {
            if (recovered >= maxClaims) {
                break;
            }
            int tenantCount = tenantClaims.getOrDefault(execution.tenantId(), 0);
            if (tenantCount >= properties.normalizedRecoveryMaxClaimsPerTenantPerScan()) {
                continue;
            }
            if (recoverOne(execution)) {
                recovered++;
                tenantClaims.put(execution.tenantId(), tenantCount + 1);
            }
        }
        return recovered;
    }

    private boolean recoverOne(ChatRunExecution candidate) {
        ChatRunRecoveryCapacityLimiter.Permit recoveryPermit = capacityLimiter.tryAcquireRecovery();
        if (recoveryPermit == null) {
            return false;
        }
        try (recoveryPermit) {
            Optional<ChatRun> run = runRepository.findById(candidate.runId());
            if (run.isEmpty() || run.get().status().terminal()) {
                executionRepository.markTerminal(candidate.runId(), statusFromRun(run.orElse(null)));
                return false;
            }
            String instanceId = instanceIdProvider.currentInstanceId();
            if (properties.isRecoverLockEnabled()
                    && !recoverLock.tryLock(candidate.runId(), instanceId, properties.normalizedRecoverLockTtl())) {
                return false;
            }
            if (run.get().status() == ChatRunStatus.CANCELLING
                    && reconciliationService.terminalCommitAvailable()) {
                return reconciliationService.recoverCancellingRun(run.get(), candidate, instanceId);
            }
            return recoverWithStrategyChain(run.get(), candidate, instanceId);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                            "Stale run recovery failed")
                    .runId(candidate.runId())
                    .operation("run-recovery.execute")
                    .build(), ex);
            return false;
        }
    }

    // This ordered strategy chain is a claim-once recovery state machine; takeover permits must retain their exact
    // scope and every strategy must observe the same fenced execution snapshot.
    @SuppressWarnings("PMD.CognitiveComplexity")
    private boolean recoverWithStrategyChain(ChatRun run, ChatRunExecution candidate, String instanceId) {
        ChatRunExecution currentExecution = candidate;
        boolean claimed = false;
        for (String configuredName : properties.normalizedStaleRecoveryStrategies()) {
            String strategyName = normalize(configuredName);
            StaleRunRecoveryStrategy strategy = strategyRegistry.find(strategyName);
            if (strategy == null) {
                continue;
            }
            StaleRunRecoveryContext preClaimContext = new StaleRunRecoveryContext(run, currentExecution, instanceId, strategyName);
            if (!strategy.supports(preClaimContext)) {
                continue;
            }
            ChatRunRecoveryCapacityLimiter.Permit takeoverPermit = null;
            if (RuntimeTakeoverRecoveryStrategy.NAME.equals(strategyName)) {
                takeoverPermit = capacityLimiter.tryAcquireTakeover();
                if (takeoverPermit == null) {
                    continue;
                }
            }
            try {
                if (!claimed) {
                    Optional<ChatRunExecution> claimedExecution = executionRepository.tryClaimRecovering(
                            run.id(),
                            instanceId,
                            strategyName,
                            properties.normalizedLeaseDuration()
                    );
                    if (claimedExecution.isEmpty()) {
                        return false;
                    }
                    currentExecution = claimedExecution.get();
                    claimed = true;
                }
                StaleRunRecoveryResult result = strategy.recover(
                        new StaleRunRecoveryContext(run, currentExecution, instanceId, strategyName));
                if (result.recovered()) {
                    log.info("stale run recovered. runId={}, strategy={}, message={}",
                            run.id(), result.strategy(), result.message());
                    return true;
                }
            } finally {
                if (takeoverPermit != null) {
                    takeoverPermit.close();
                }
            }
        }
        return false;
    }

    private String normalize(String strategyName) {
        return strategyName == null ? "" : strategyName.trim().toUpperCase(Locale.ROOT);
    }

    private com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus statusFromRun(ChatRun run) {
        if (run == null || run.status() == null || !run.status().terminal()) {
            return com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus.FAILED;
        }
        return switch (run.status()) {
            case COMPLETED -> com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus.COMPLETED;
            case WAITING_USER -> com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus.WAITING_USER;
            case CANCELLED -> com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus.CANCELLED;
            case FAILED -> com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus.FAILED;
            default -> com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus.FAILED;
        };
    }
}
