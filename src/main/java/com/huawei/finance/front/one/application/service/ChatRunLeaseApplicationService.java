package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.config.ChatRunOperationalProperties;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunExecution;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Chat run 租约应用服务。
 *
 * <p>该服务负责控制面 execution 的创建、心跳、终态同步和事件写入栅栏校验。它不负责业务
 * run 状态迁移，也不直接发布前端事件。</p>
 */
@Service
public class ChatRunLeaseApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ChatRunLeaseApplicationService.class);

    private final ChatRunExecutionRepository executionRepository;
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final ChatRunOperationalProperties properties;
    private final IdGenerator idGenerator;
    private final LocalChatRunExecutionRegistry executionRegistry;

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
     * 当前 owner 主动刷新 run 租约。
     *
     * @param claim 当前执行流写入权声明。
     * @return true 表示刷新成功。
     */
    public boolean heartbeat(RunExecutionClaim claim) {
        if (claim == null) {
            return false;
        }
        return executionRepository.heartbeat(claim.runId(), claim.ownerInstanceId(), properties.normalizedLeaseDuration());
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
     * <p>使用本机 registry 的 claim 快照执行 heartbeat。刷新失败通常说明 run 已终态、被 stop、
     * 被 watchdog 抢占，或当前实例已经不再拥有写入权；失败只记录日志，后续事件写入栅栏会负责阻断。</p>
     */
    @Scheduled(fixedDelayString = "#{@chatRunOperationalProperties.normalizedHeartbeatInterval().toMillis()}")
    void heartbeatActiveRuns() {
        for (RunExecutionClaim claim : executionRegistry.activeClaims()) {
            try {
                if (!heartbeat(claim)) {
                    log.debug("ChatRun heartbeat skipped or rejected. runId={}, owner={}, token={}",
                            claim.runId(), claim.ownerInstanceId(), claim.fencingToken());
                }
            } catch (RuntimeException ex) {
                log.warn("ChatRun heartbeat failed. runId={}, reason={}", claim.runId(), ex.getMessage());
            }
        }
    }
}
