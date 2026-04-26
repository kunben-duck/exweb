package com.huawei.finance.front.one.infrastructure.intent;

import com.huawei.finance.front.one.application.gateway.IntentRecognizer;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "financeex.intent.provider", havingValue = "mock", matchIfMissing = true)
public class MockIntentRecognizer implements IntentRecognizer {
    @Override
    public IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user) {
        String msg = command.message() == null ? "" : command.message();
        if (msg.contains("不支持")) return new IntentDecision("unsupported", "不支持任务", TaskComplexity.UNSUPPORTED, 0.9, false, Map.of(), Map.of());
        if (msg.contains("复杂") || msg.contains("报表") || msg.contains("分析")) return new IntentDecision("finance.complex", "复杂财经任务", TaskComplexity.COMPLEX, 0.92, false, Map.of(), Map.of());
        return new IntentDecision("finance.simple", "简单财经任务", TaskComplexity.SIMPLE, 0.90, true, Map.of(), Map.of());
    }
    @Override public String provider() { return "mock"; }
}
