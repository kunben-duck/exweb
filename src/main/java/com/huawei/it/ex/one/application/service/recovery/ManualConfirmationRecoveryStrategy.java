/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.recovery;

import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatRunTerminalCommitService;
import com.huawei.it.ex.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * stale run 的默认人工确认恢复策略。
 *
 * <p>该策略不自动接管、不自动重试，而是把原 run 以 run.failed 闭合，并在 payload 中提示前端
 * 展示“重新生成/重新发送”等恢复入口。这是当前 Runtime 不具备可靠断点恢复能力时最稳的生产策略。</p>
 */
@Service
public class ManualConfirmationRecoveryStrategy implements StaleRunRecoveryStrategy {
    private static final AppLogger log = AppLoggerFactory.getLogger(ManualConfirmationRecoveryStrategy.class);
    public static final String NAME = "MANUAL_CONFIRMATION";
    static final String RUN_EXECUTOR_LOST = "RUN_EXECUTOR_LOST";

    private final ChatStreamApplicationService streamService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatRunApplicationService runService;

    public ManualConfirmationRecoveryStrategy(ChatStreamApplicationService streamService,
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
                RUN_EXECUTOR_LOST,
                "执行实例心跳超时，本轮回答已中断",
                failurePayload(context, true)
        );
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.recovery(
                                event, context.run(), context.execution(), context.instanceId()));
        if (result.committed()) {
            runService.synchronizeCommittedRunCache(result.run());
            publishTerminalBestEffort(result.event());
            return StaleRunRecoveryResult.recovered(NAME, "stale run failed with manual confirmation options");
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "Recovered run terminal event was committed but realtime publication failed")
                    .runId(event == null ? null : event.runId())
                    .operation("run-recovery.terminal.publish")
                    .build());
        }
    }

    protected Map<String, Object> failurePayload(StaleRunRecoveryContext context, boolean recoveryActionRequired) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", RUN_EXECUTOR_LOST);
        payload.put("message", "执行实例心跳超时，本轮回答已中断");
        payload.put("recoveryStrategy", strategyName());
        payload.put("recoveryActionRequired", recoveryActionRequired);
        if (recoveryActionRequired) {
            payload.put("recoveryOptions", List.of("REGENERATE_ASSISTANT", "RETRY_AS_NEW_RUN"));
        }
        payload.put("ownerInstanceId", context.execution().ownerInstanceId());
        payload.put("recoveredByInstanceId", context.instanceId());
        payload.put("heartbeatAt", context.execution().heartbeatAt());
        payload.put("leaseUntil", context.execution().leaseUntil());
        return payload;
    }
}
