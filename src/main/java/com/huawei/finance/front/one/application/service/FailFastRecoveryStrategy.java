package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ErrorEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * stale run 的保底失败闭合策略。
 *
 * <p>该策略直接把 stale run 标记为失败，不要求前端提供人工确认选项。它通常作为策略链最后一项，
 * 保证任何无法接管或无法人工确认的场景都不会让会话永久卡在 RUNNING。</p>
 */
@Service
public class FailFastRecoveryStrategy implements StaleRunRecoveryStrategy {
    public static final String NAME = "FAIL_FAST";

    private final ChatStreamApplicationService streamService;
    private final ChatRunApplicationService runService;
    private final ChatRunLeaseApplicationService leaseService;

    public FailFastRecoveryStrategy(ChatStreamApplicationService streamService,
                                    ChatRunApplicationService runService,
                                    ChatRunLeaseApplicationService leaseService) {
        this.streamService = streamService;
        this.runService = runService;
        this.leaseService = leaseService;
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
    @Transactional
    public StaleRunRecoveryResult recover(StaleRunRecoveryContext context) {
        ChatEvent event = ErrorEvent.of(
                context.run().id(),
                context.run().sessionId(),
                ManualConfirmationRecoveryStrategy.RUN_EXECUTOR_LOST,
                "执行实例心跳超时，本轮回答已失败",
                failurePayload(context)
        );
        ChatEvent stored = streamService.appendAndPublish(event);
        runService.observeEvent(stored);
        leaseService.markTerminal(context.run().id(), ChatRunExecutionStatus.FAILED);
        return StaleRunRecoveryResult.recovered(NAME, "stale run failed fast");
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
