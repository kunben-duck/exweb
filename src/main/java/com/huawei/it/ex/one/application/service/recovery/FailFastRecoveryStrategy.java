package com.huawei.it.ex.one.application.service.recovery;

import com.huawei.it.ex.one.application.service.chat.ChatRunTerminalCommitService;
import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * stale run 的保底失败闭合策略。
 *
 * <p>该策略直接把 stale run 标记为失败，不要求前端提供人工确认选项。它通常作为策略链最后一项，
 * 保证任何无法接管或无法人工确认的场景都不会让会话永久卡在 RUNNING。</p>
 */
@Service
public class FailFastRecoveryStrategy implements StaleRunRecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(FailFastRecoveryStrategy.class);
    public static final String NAME = "FAIL_FAST";

    private final ChatStreamApplicationService streamService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatRunApplicationService runService;

    public FailFastRecoveryStrategy(ChatStreamApplicationService streamService,
                                    ChatRunTerminalCommitService terminalCommitService,
                                    ChatRunApplicationService runService) {
        this.streamService = streamService;
        this.terminalCommitService = terminalCommitService;
        this.runService = runService;
    }

    @Override
    public String strategyName() {
        return NAME;
    }

    @Override
    public boolean supports(StaleRunRecoveryContext context) {
        return context != null && context.run() != null && context.execution() != null;
    }

    @Override
    public StaleRunRecoveryResult recover(StaleRunRecoveryContext context) {
        ChatEvent event = ErrorEvent.of(
                context.run().id(),
                context.run().sessionId(),
                ManualConfirmationRecoveryStrategy.RUN_EXECUTOR_LOST,
                "执行实例心跳超时，本轮回答已失败",
                failurePayload(context)
        );
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.recovery(
                                event, context.run(), context.execution(), context.instanceId()));
        if (result.committed()) {
            runService.synchronizeCommittedRunCache(result.run());
            publishTerminalBestEffort(result.event());
            return StaleRunRecoveryResult.recovered(NAME, "stale run failed fast");
        }
        if (result.run() != null && result.run().status().terminal()) {
            runService.synchronizeCommittedRunCache(result.run());
            return StaleRunRecoveryResult.recovered(NAME, "stale run already reached terminal state");
        }
        return StaleRunRecoveryResult.skipped(NAME, "stale run terminal claim lost to another recovery owner");
    }

    private void publishTerminalBestEffort(ChatEvent event) {
        try {
            streamService.publishPersisted(event);
        } catch (RuntimeException ex) {
            log.warn("Recovered run terminal event committed but realtime publish failed. runId={}, reason={}",
                    event == null ? null : event.runId(), ex.getMessage(), ex);
        }
    }

    private Map<String, Object> failurePayload(StaleRunRecoveryContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", ManualConfirmationRecoveryStrategy.RUN_EXECUTOR_LOST);
        payload.put("message", "执行实例心跳超时，本轮回答已失败");
        payload.put("recoveryStrategy", NAME);
        payload.put("recoveryActionRequired", false);
        payload.put("ownerInstanceId", context.execution().ownerInstanceId());
        payload.put("recoveredByInstanceId", context.instanceId());
        payload.put("heartbeatAt", context.execution().heartbeatAt());
        payload.put("leaseUntil", context.execution().leaseUntil());
        return payload;
    }
}
