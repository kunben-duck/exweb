package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chat run 租约应用服务。
 *
 * <p>该服务负责控制面 execution 的创建、心跳、终态同步和事件写入栅栏校验。它不负责业务
 * run 状态迁移，也不直接发布前端事件。</p>
 */
@Service
public class ChatRunLeaseApplicationService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunLeaseApplicationService.class);

    private final ChatRunExecutionRepository executionRepository;
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final ChatRunOperationalProperties properties;
    private final IdGenerator idGenerator;
    private final LocalChatRunExecutionRegistry executionRegistry;
    private RuntimeStreamLimitsProperties streamLimitsProperties = new RuntimeStreamLimitsProperties();

    public ChatRunLeaseApplicationService(ChatRunExecutionRepository executionRepository,
                                          ApplicationInstanceIdProvider instanceIdProvider,
                                          ChatRunOperationalProperties properties,
                                          IdGenerator idGenerator,
                                          LocalChatRunExecutionRegistry executionRegistry) {
        this.executionRepository = executionRepository;
        this.instanceIdProvider = instanceIdProvider;
        this.properties = properties;
        this.idGenerator = idGenerator;
        this.executionRegistry = executionRegistry;
    }

    /** 保持直接构造的存量测试兼容；生产上下文注入统一stop收口租约配置。 */
    @Autowired(required = false)
    void setRuntimeStreamLimitsProperties(RuntimeStreamLimitsProperties streamLimitsProperties) {
        if (streamLimitsProperties != null) {
            this.streamLimitsProperties = streamLimitsProperties;
        }
    }

    /**
     * 为新创建的 run 初始化 execution 控制面，并返回当前执行流的写入权 claim。
     *
     * @param run 已创建为 RUNNING 的业务 run。
     * @return 当前执行流持有的写入权声明。
     */
    public RunExecutionClaim startRun(ChatRun run) {
        String executionId = idGenerator.newId("exec",
                IdGenerateContext.of(run.tenantId(), run.userId(), run.sessionId(), run.id()));
        ChatRunExecution execution = executionRepository.createForRun(
                run,
                executionId,
                instanceIdProvider.currentInstanceId(),
                properties.normalizedLeaseDuration()
        );
        return new RunExecutionClaim(run.id(), execution.ownerInstanceId(), execution.fencingToken());
    }

    /**
     * 初始化 Interaction continuation execution，并阻止已被 watchdog 回收的迟到启动。
     */
    public RunExecutionClaim startInteractionRun(ChatRun run, String interactionId) {
        String executionId = idGenerator.newId("exec",
                IdGenerateContext.of(run.tenantId(), run.userId(), run.sessionId(), run.id()));
        ChatRunExecution execution = executionRepository.createForInteractionRun(
                run,
                executionId,
                instanceIdProvider.currentInstanceId(),
                properties.normalizedLeaseDuration(),
                interactionId
        );
        return new RunExecutionClaim(run.id(), execution.ownerInstanceId(), execution.fencingToken());
    }

    /**
     * 当前 owner 主动刷新 run 租约。
     *
     * @param claim 当前执行流写入权声明。
     * @return true 表示刷新成功。
     */
    public boolean heartbeat(RunExecutionClaim claim) {
        if (claim == null) {
            return false;
        }
        return executionRepository.heartbeat(
                claim.runId(),
                claim.ownerInstanceId(),
                claim.fencingToken(),
                properties.normalizedLeaseDuration());
    }

    /**
     * 判断当前执行流是否仍可启动新的外部副作用。
     */
    public boolean isCurrentOwnerRunning(RunExecutionClaim claim) {
        return executionRepository.isCurrentOwnerRunning(claim);
    }

    /** 返回指定run当前持久化的execution控制面快照。 */
    public Optional<ChatRunExecution> findExecution(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return executionRepository.findByRunId(runId);
    }

    /** run已收到stop时缩短当前RUNNING execution租约，重复调用不会延长截止时间。 */
    public boolean shortenLeaseForStop(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        return executionRepository.shortenLeaseForCancellingRun(
                runId, streamLimitsProperties.getStopFinalizationLease());
    }

    /** 当前owner以完整claim取得stop终态独占收口权。 */
    public boolean markOwnerStopAccepted(ChatRun run, RunExecutionClaim claim) {
        return executionRepository.markOwnerStopAccepted(
                run, claim, streamLimitsProperties.getStopFinalizationLease());
    }

    /** execution是否已由owner接管stop收口，或已进入恢复抢占。 */
    public boolean stopFallbackBlocked(String runId) {
        return findExecution(runId)
                .map(ChatRunExecution::executionStatus)
                .map(status -> status == ChatRunExecutionStatus.CANCELLING
                        || status == ChatRunExecutionStatus.RECOVERING)
                .orElse(false);
    }

    /** 返回当前JVM稳定的实例标识。 */
    public String currentInstanceId() {
        return instanceIdProvider.currentInstanceId();
    }

    /**
     * run 进入终态后同步 execution 状态。
     *
     * @param runId run 标识。
     * @param terminalStatus execution 终态。
     */
    public void markTerminal(String runId, ChatRunExecutionStatus terminalStatus) {
        if (runId == null || terminalStatus == null || !terminalStatus.terminal()) {
            return;
        }
        executionRepository.markTerminal(runId, terminalStatus);
    }

    /**
     * 判断 run execution 租约是否已经过期。
     *
     * @param runId run 标识。
     * @return true 表示租约已过期。
     */
    public boolean isLeaseExpired(String runId) {
        return executionRepository.isLeaseExpired(runId, Instant.now());
    }

    /**
     * 定期刷新本 JVM 正在执行的 run 租约。
     *
     * <p>使用本机 registry 的 claim 快照执行 heartbeat。数据库明确拒绝当前完整 claim 时，
     * 条件取消对应的本机后台订阅；数据库异常只记录日志，保留订阅并由后续 heartbeat 或事件写入栅栏处理。</p>
     */
    @Scheduled(fixedDelayString = "#{@chatRunOperationalProperties.normalizedHeartbeatInterval().toMillis()}")
    void heartbeatActiveRuns() {
        // 固定批次顺序，避免多行更新在不同轮次采用不稳定的锁访问顺序。
        List<RunExecutionClaim> claims = executionRegistry.activeClaims().stream()
                .sorted(Comparator.comparing(RunExecutionClaim::runId))
                .toList();
        int batchSize = properties.normalizedHeartbeatBatchSize();
        for (int from = 0; from < claims.size(); from += batchSize) {
            int to = Math.min(from + batchSize, claims.size());
            // 每批由仓储开启独立短事务；单批失败后仍可继续续租后续批次。
            renewHeartbeatBatch(List.copyOf(claims.subList(from, to)));
        }
    }

    private void renewHeartbeatBatch(List<RunExecutionClaim> claims) {
        try {
            List<RunExecutionClaim> renewedClaims = Objects.requireNonNull(
                    executionRepository.heartbeatBatch(claims, properties.normalizedLeaseDuration()),
                    "heartbeatBatch result");
            Set<RunExecutionClaim> renewed = new HashSet<>(renewedClaims);
            for (RunExecutionClaim claim : claims) {
                // 只有数据库明确未返回的完整 claim 才能取消，数据库异常不能视为 owner 失效。
                if (!renewed.contains(claim)) {
                    log.debug("ChatRun heartbeat skipped or rejected. runId={}, owner={}, token={}",
                            claim.runId(), claim.ownerInstanceId(), claim.fencingToken());
                    executionRegistry.cancel(claim);
                }
            }
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                            "ChatRun execution lease heartbeat batch failed")
                    .operation("run-execution.heartbeat.batch")
                    .attribute("batchSize", claims.size())
                    .attribute("firstRunId", claims.getFirst().runId())
                    .build(), ex);
        }
    }
}
