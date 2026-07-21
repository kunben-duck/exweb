package com.huawei.it.ex.one.chat.application.recovery;

import com.huawei.it.ex.one.chat.application.mapper.ChatRuntimeMapper;

import com.huawei.it.ex.one.runtime.application.service.RuntimeRecoveryService;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeRecoveryRequest;
import org.springframework.stereotype.Service;

/**
 * Runtime 接管续跑策略。
 *
 * <p>该策略仅在 Runtime 明确声明支持可靠断点恢复时才会被选择。当前内置 Relay 恢复端口默认
 * {@code supports=false}，因此正式首版会自动降级到 MANUAL_CONFIRMATION。</p>
 */
@Service
public class RuntimeTakeoverRecoveryStrategy implements StaleRunRecoveryStrategy {
    public static final String NAME = "RUNTIME_TAKEOVER";

    private final RuntimeRecoveryService recoveryPort;

    public RuntimeTakeoverRecoveryStrategy(RuntimeRecoveryService recoveryPort) {
        this.recoveryPort = recoveryPort;
    }

    @Override
    public String strategyName() {
        return NAME;
    }

    @Override
    public boolean supports(StaleRunRecoveryContext context) {
        if (context == null || context.run() == null || context.execution() == null) {
            return false;
        }
        if (context.run().runtimeSessionId() == null || context.run().runtimeSessionId().isBlank()) {
            return false;
        }
        if (context.execution().runtimeResumeToken() == null || context.execution().runtimeResumeToken().isBlank()) {
            return false;
        }
        return recoveryPort.supports(request(context));
    }

    @Override
    public StaleRunRecoveryResult recover(StaleRunRecoveryContext context) {
        /*
         * 当前默认 Runtime 不支持该分支。真正接管需要 Runtime 保证 resumeToken 后的输出不会重复
         * 已落库 seq，并且需要把恢复流重新接入 FinanceEXChatService 的完整消息保存流程。
         * 因此这里保守返回 skipped，orchestrator 会继续尝试后续 MANUAL_CONFIRMATION/FAIL_FAST。
         */
        return StaleRunRecoveryResult.skipped(NAME, "runtime takeover is not enabled by current runtime adapter");
    }

    private AgentRuntimeRecoveryRequest request(StaleRunRecoveryContext context) {
        long lastSeq = context.run().lastSeq() == null ? 0L : context.run().lastSeq();
        return new AgentRuntimeRecoveryRequest(
                ChatRuntimeMapper.run(context.run()),
                ChatRuntimeMapper.execution(context.execution()),
                context.execution().runtimeResumeToken(),
                lastSeq
        );
    }
}
