package com.huawei.finance.front.one.infrastructure.subagent;

import com.huawei.finance.front.one.domain.agent.AgentQueryRequest;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.task.RequiredInput;
import com.huawei.finance.front.one.domain.task.SubAgentTaskRequest;
import com.huawei.finance.front.one.domain.task.TaskCard;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 自然语言 SubAgent 契约 Prompt 构造器。
 *
 * <p>短期内第三方 SubAgent 可能只是对话式 Agent，而不是稳定 API。SuperAgent 通过增强 Prompt
 * 明确任务边界、已有状态和 JSON 输出契约，再由 normalizer 兜底解析响应。</p>
 */
@Component
public class SubAgentTaskPromptBuilder {
    private static final String OUTPUT_CONTRACT = """
            必须只返回一个 JSON 对象，不要输出 JSON 之外的任何文本。字段如下：
            {
              "message": "面向用户的中文回复",
              "taskStatus": "ACTIVE|REQUIRES_USER_INPUT|WAITING_EXTERNAL_SYSTEM|WAITING_USER_CONFIRMATION|COMPLETED|FAILED|CANCELLED",
              "requiredInputs": [{"name":"字段名","description":"需要用户补充的内容","type":"string|number|document|image","required":true}],
              "agentSessionId": "你的会话 ID，没有则为空",
              "businessObjectRefs": [{"objectType":"业务对象类型","objectId":"业务对象 ID","displayName":"展示名称","attributes":{}}],
              "confidence": 0.0
            }
            """;

    /**
     * 构造发给自然语言 SubAgent 的增强请求。
     *
     * @param request SuperAgent 下游调用请求。
     * @param endpoint 当前 SubAgent 配置。
     * @return 增强任务请求。
     */
    public SubAgentTaskRequest build(AgentQueryRequest request, SubAgentProperties.AgentEndpoint endpoint) {
        TaskCard taskCard = request.taskCard();
        String taskGoal = firstNonBlank(endpoint.getTaskGoal(), taskCard == null ? null : taskCard.taskGoal(), "处理财经任务");
        String taskDomain = firstNonBlank(endpoint.getTaskDomain(), taskCard == null ? null : taskCard.taskDomain(), "finance_task");
        String prompt = prompt(request, taskCard, taskGoal, taskDomain);
        return new SubAgentTaskRequest(
                taskCard == null ? null : taskCard.taskId(),
                request.runId(),
                request.tenantId(),
                request.userId(),
                request.sessionId(),
                request.agentCode(),
                request.agentSessionId(),
                taskGoal,
                taskDomain,
                request.message(),
                taskCard,
                prompt,
                OUTPUT_CONTRACT,
                request.attachments(),
                request.memoryContext(),
                request.metadata()
        );
    }

    private String prompt(AgentQueryRequest request, TaskCard taskCard, String taskGoal, String taskDomain) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是一个独立的财经业务 SubAgent，只处理 SuperAgent 指定的当前任务。\n");
        builder.append("任务目标: ").append(taskGoal).append('\n');
        builder.append("任务领域: ").append(taskDomain).append('\n');
        builder.append("用户本轮输入: ").append(nullToEmpty(request.message())).append('\n');
        builder.append("重要规则:\n");
        builder.append("1. 只推进当前任务，不要自行切换到其他任务。\n");
        builder.append("2. 不得臆造业务系统结果；如果缺少发票号、金额、附件等材料，返回 REQUIRES_USER_INPUT。\n");
        builder.append("3. 如果已提交业务系统但还没有最终结果，返回 WAITING_EXTERNAL_SYSTEM。\n");
        builder.append("4. 如果任务已完成，返回 COMPLETED，并在 businessObjectRefs 中返回业务对象引用。\n");
        builder.append("5. 如果无法确定状态，返回 WAITING_USER_CONFIRMATION 并给出澄清问题。\n\n");
        builder.append("当前任务快照:\n");
        if (taskCard == null) {
            builder.append("- 无历史任务卡片，本轮是任务首轮。\n");
        } else {
            builder.append("- taskId: ").append(taskCard.taskId()).append('\n');
            builder.append("- taskStatus: ").append(taskCard.taskStatus()).append('\n');
            builder.append("- agentSessionId: ").append(nullToEmpty(taskCard.agentSessionId())).append('\n');
            builder.append("- requiredInputs: ").append(requiredInputs(taskCard.requiredInputs())).append('\n');
            builder.append("- collectedSlots: ").append(taskCard.collectedSlots()).append('\n');
            builder.append("- lastAgentMessage: ").append(nullToEmpty(taskCard.lastAgentMessage())).append('\n');
        }
        builder.append("本轮附件:\n").append(attachments(request.attachments())).append('\n');
        builder.append("输出契约:\n").append(OUTPUT_CONTRACT);
        return builder.toString();
    }

    private String requiredInputs(List<RequiredInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return "[]";
        }
        return inputs.toString();
    }

    private String attachments(List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "[]";
        }
        return attachments.stream()
                .map(ref -> Map.of("documentId", nullToEmpty(ref.documentId()),
                        "name", nullToEmpty(ref.name()),
                        "contentType", nullToEmpty(ref.contentType())).toString())
                .toList()
                .toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
