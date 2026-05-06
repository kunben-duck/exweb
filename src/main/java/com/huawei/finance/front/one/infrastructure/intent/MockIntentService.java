package com.huawei.finance.front.one.infrastructure.intent;

import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 意图服务。
 *
 * <p>第一版用于本地启动和前端联调，真实生产环境可以替换为第三方 IntentService 实现。</p>
 */
@Component
@ConditionalOnProperty(name = "financeex.intent.provider", havingValue = "mock", matchIfMissing = true)
public class MockIntentService implements IntentService {
    @Override
    public IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user) {
        String message = command.message() == null ? "" : command.message().trim();
        if (message.contains("不支持")) {
            return new IntentDecision("unsupported", "不支持任务", TaskComplexity.UNSUPPORTED, 0.95, false,
                    null, Map.of(), List.of(), Map.of("source", "mock"));
        }
        if (isComplex(message)) {
            return new IntentDecision("finance.complex", "复杂财经任务", TaskComplexity.COMPLEX, 0.92, false,
                    null, Map.of(), List.of(), Map.of("source", "mock"));
        }
        if (message.contains("员工") || message.contains("工号")) {
            Map<String, Object> slots = employeeSlots(message);
            return new IntentDecision("finance.employee.query", "员工信息查询", TaskComplexity.SIMPLE, 0.91, true,
                    "finance.employee.agent", slots, missing(slots, "employeeNo", "employeeName"), Map.of("source", "mock"));
        }
        if (message.contains("代表处") || message.contains("办事处") || message.contains("国家")) {
            Map<String, Object> slots = officeSlots(message);
            return new IntentDecision("finance.office.query", "代表处办事处查询", TaskComplexity.SIMPLE, 0.91, true,
                    "finance.office.agent", slots, missing(slots, "country", "repOffice"), Map.of("source", "mock"));
        }
        return new IntentDecision("finance.simple.answer", "简单财经问答", TaskComplexity.SIMPLE, 0.90, true,
                null, Map.of(), List.of(), Map.of("source", "mock"));
    }

    @Override
    public String provider() {
        return "mock";
    }

    private boolean isComplex(String message) {
        return message.contains("复杂")
                || message.contains("报表")
                || message.contains("分析")
                || message.contains("规划")
                || message.contains("方案");
    }

    private Map<String, Object> employeeSlots(String message) {
        if (message.matches(".*\\d{4,}.*")) {
            return Map.of("employeeNo", message.replaceAll(".*?(\\d{4,}).*", "$1"));
        }
        return Map.of();
    }

    private Map<String, Object> officeSlots(String message) {
        if (message.contains("中国")) {
            return Map.of("country", "中国");
        }
        if (message.contains("法国")) {
            return Map.of("country", "法国");
        }
        return Map.of();
    }

    private List<String> missing(Map<String, Object> slots, String... alternatives) {
        for (String alternative : alternatives) {
            if (slots.containsKey(alternative)) {
                return List.of();
            }
        }
        return List.of(String.join("|", alternatives));
    }
}
