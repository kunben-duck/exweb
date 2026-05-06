package com.huawei.finance.front.one.infrastructure.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.task.TaskStatus;
import org.junit.jupiter.api.Test;

class SubAgentResponseNormalizerTest {
    private final SubAgentResponseNormalizer normalizer = new SubAgentResponseNormalizer(new ObjectMapper());

    @Test
    void parsesStandardJson() {
        var result = normalizer.normalize("""
                {"message":"请提供发票号","taskStatus":"REQUIRES_USER_INPUT","requiredInputs":[{"name":"invoiceNo","description":"发票号","type":"string","required":true}],"confidence":0.91}
                """);

        assertThat(result.taskStatus()).isEqualTo(TaskStatus.REQUIRES_USER_INPUT);
        assertThat(result.requiredInputs()).extracting("name").containsExactly("invoiceNo");
    }

    @Test
    void parsesMarkdownJsonBlockAndAliases() {
        var result = normalizer.normalize("""
                ```json
                {"reply":"提交成功","status":"success","sessionId":"agent-session-1"}
                ```
                """);

        assertThat(result.taskStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.agentSessionId()).isEqualTo("agent-session-1");
    }

    @Test
    void infersRequiredInputFromText() {
        var result = normalizer.normalize("请上传发票图片后我继续处理");

        assertThat(result.taskStatus()).isEqualTo(TaskStatus.REQUIRES_USER_INPUT);
        assertThat(result.requiredInputs()).extracting("name").containsExactly("invoiceImage");
    }

    @Test
    void unknownTextTurnsIntoWaitingUserConfirmation() {
        var result = normalizer.normalize("好的，我先看一下");

        assertThat(result.taskStatus()).isEqualTo(TaskStatus.WAITING_USER_CONFIRMATION);
        assertThat(result.rawNormalizedStatus()).isEqualTo(TaskStatus.UNKNOWN);
        assertThat(result.confirmationQuestion()).contains("继续处理刚才的报销单");
    }
}
