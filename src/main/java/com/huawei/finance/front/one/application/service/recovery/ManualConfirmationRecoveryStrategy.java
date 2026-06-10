package com.huawei.finance.front.one.application.service.recovery;

import com.huawei.finance.front.one.application.service.chat.ChatRunApplicationService;
import com.huawei.finance.front.one.application.service.chat.ChatRunLeaseApplicationService;
import com.huawei.finance.front.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ErrorEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * stale run 的默认人工确认恢复策略。
 *
 * <p>该策略不自动接管、不自动重试，而是把原 run 以 run.failed 闭合，并在 payload 中提示前端
 * 展示“重新生成/重新发送”等恢复入口。这是当前 Runtime 不具备可靠断点恢复能力时最稳的生产策略。</p>
 */
@Service
public class ManualConfirmationRecoveryStrategy implements StaleRunRecoveryStrategy {
    public static final String NAME = "MANUAL_CONFIRMATION";
    static final String RUN_EXECUTOR_LOST = "RUN_EXECUTOR_LOST";

    private final ChatStreamApplicationService streamService;
    private final ChatRunApplicationService runService;
    private final ChatRunLeaseApplicationService leaseService;

    public ManualConfirmationRecoveryStrategy(ChatStreamApplicationService streamService,
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
                RUN_EXECUTOR_LOST,
                "执行实例心跳超时，本轮回答已中断",
                failurePayload(context, true)
        );
        ChatEvent stored = streamService.appendAndPublish(event);
        runService.observeEvent(stored);
        leaseService.markTerminal(context.run().id(), ChatRunExecutionStatus.FAILED);
        return StaleRunRecoveryResult.recovered(NAME, "stale run failed with manual confirmation options");
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
