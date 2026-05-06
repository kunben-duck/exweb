package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.task.ContinuationDecision;
import com.huawei.finance.front.one.domain.task.RequiredInput;
import com.huawei.finance.front.one.domain.task.TaskCard;
import com.huawei.finance.front.one.domain.task.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContinuationGuardTest {
    private final ContinuationGuard guard = new ContinuationGuard();

    @Test
    void invoiceNumberContinuesCurrentTask() {
        ContinuationGuardResult result = guard.evaluate(task(List.of(new RequiredInput("invoiceNo", "请提供发票号", "string", true))),
                command("发票号是 123456"), null);

        assertThat(result.decision()).isEqualTo(ContinuationDecision.CONTINUE_CURRENT);
    }

    @Test
    void scheduleQuestionSuspendsCurrentTask() {
        ContinuationGuardResult result = guard.evaluate(task(List.of(new RequiredInput("invoiceNo", "请提供发票号", "string", true))),
                command("帮我看下今天的日程"), null);

        assertThat(result.decision()).isEqualTo(ContinuationDecision.SUSPEND_AND_ROUTE_NEW);
    }

    @Test
    void cancelTextCancelsCurrentTask() {
        ContinuationGuardResult result = guard.evaluate(task(List.of()), command("不用了，取消这个报销"), null);

        assertThat(result.decision()).isEqualTo(ContinuationDecision.CANCEL_CURRENT);
    }

    @Test
    void ambiguousInputAsksConfirmation() {
        ContinuationGuardResult result = guard.evaluate(task(List.of(new RequiredInput("invoiceNo", "请提供发票号", "string", true))),
                command("好的"), null);

        assertThat(result.decision()).isEqualTo(ContinuationDecision.ASK_USER_CONFIRMATION);
    }

    private ChatCommand command(String message) {
        return new ChatCommand("cmd", "t", "u", "s", null, "web", "sse", null, null, message, List.of(), Map.of());
    }

    private TaskCard task(List<RequiredInput> requiredInputs) {
        Instant now = Instant.now();
        return new TaskCard("task1", "t", "u", "s", "binding1", "创建并推进员工报销单",
                "employee_reimbursement", "employee_reimbursement_agent", null,
                TaskStatus.REQUIRES_USER_INPUT, TaskStatus.REQUIRES_USER_INPUT, requiredInputs, Map.of(),
                "请提供发票号", null, now.plus(Duration.ofDays(1)), now, now, Map.of());
    }
}
