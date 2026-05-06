package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.task.ContinuationDecision;
import com.huawei.finance.front.one.domain.task.RequiredInput;
import com.huawei.finance.front.one.domain.task.TaskCard;
import com.huawei.finance.front.one.domain.task.TaskStatus;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * active task 续接守卫。
 *
 * <p>它解决“binding 不等于强粘性”的问题：即使当前会话存在报销任务，也只有用户本轮输入确实像是在
 * 补参数、确认上一轮问题、解释当前任务或上传当前任务附件时，才继续调用原 SubAgent。明显的新任务会
 * 挂起当前任务并重新进入用例库/意图路由。</p>
 */
@Component
public class ContinuationGuard {
    private static final String DEFAULT_CONFIRMATION =
            "你是要继续处理刚才的报销单，还是开始新的任务？";

    /**
     * 评估当前用户输入是否应续接 active task。
     *
     * @param taskCard 当前 active task。
     * @param command 本轮聊天命令。
     * @param shadowRoute 可选 shadow route 结果；仅用于模糊场景，不会创建 binding。
     * @return 续接判断结果。
     */
    public ContinuationGuardResult evaluate(TaskCard taskCard, ChatCommand command, RouteTarget shadowRoute) {
        String text = normalize(command == null ? null : command.message());
        List<AttachmentRef> attachments = command == null || command.attachments() == null ? List.of() : command.attachments();

        if (taskCard == null) {
            return ContinuationGuardResult.of(ContinuationDecision.ROUTE_NEW, "no active task");
        }
        if (containsAny(text, "取消", "不用了", "不用处理", "终止", "结束这个", "先不报销")) {
            return ContinuationGuardResult.of(ContinuationDecision.CANCEL_CURRENT, "user cancelled active task");
        }
        if (isExplicitResume(text)) {
            return ContinuationGuardResult.of(ContinuationDecision.CONTINUE_CURRENT, "user explicitly resumes active task");
        }
        if (isExplicitSwitch(text)) {
            return ContinuationGuardResult.of(ContinuationDecision.SUSPEND_AND_ROUTE_NEW, "user explicitly switches task");
        }
        if (!attachments.isEmpty() && expectsAttachment(taskCard)) {
            return ContinuationGuardResult.of(ContinuationDecision.CONTINUE_CURRENT, "attachment matches required input");
        }
        if (answersRequiredInput(taskCard, text)) {
            return ContinuationGuardResult.of(ContinuationDecision.CONTINUE_CURRENT, "user answered required input");
        }
        if (asksAboutCurrentTask(text)) {
            return ContinuationGuardResult.of(ContinuationDecision.CONTINUE_CURRENT, "user asks about current task");
        }
        if (isClearlyNewTask(text)) {
            return ContinuationGuardResult.of(ContinuationDecision.SUSPEND_AND_ROUTE_NEW, "user asks a clearly new task");
        }
        if (shadowRoute != null) {
            return evaluateShadowRoute(taskCard, shadowRoute);
        }
        if (taskCard.taskStatus() == TaskStatus.WAITING_USER_CONFIRMATION) {
            return new ContinuationGuardResult(ContinuationDecision.ASK_USER_CONFIRMATION,
                    "task already waits user confirmation", confirmationQuestion(taskCard));
        }
        if (taskCard.taskStatus() == TaskStatus.REQUIRES_USER_INPUT) {
            return new ContinuationGuardResult(ContinuationDecision.ASK_USER_CONFIRMATION,
                    "requires user input but current message does not match required inputs", confirmationQuestion(taskCard));
        }
        return new ContinuationGuardResult(ContinuationDecision.ASK_USER_CONFIRMATION,
                "ambiguous active task continuation", confirmationQuestion(taskCard));
    }

    private ContinuationGuardResult evaluateShadowRoute(TaskCard taskCard, RouteTarget shadowRoute) {
        if (shadowRoute.type() == RouteType.SUB_AGENT
                && shadowRoute.score() >= 0.85
                && shadowRoute.selectedAgentCode() != null
                && !shadowRoute.selectedAgentCode().equals(taskCard.agentCode())) {
            return ContinuationGuardResult.of(ContinuationDecision.SUSPEND_AND_ROUTE_NEW,
                    "shadow route matched different subagent");
        }
        if (shadowRoute.type() == RouteType.SUB_AGENT && taskCard.agentCode().equals(shadowRoute.selectedAgentCode())) {
            return ContinuationGuardResult.of(ContinuationDecision.CONTINUE_CURRENT,
                    "shadow route matched current subagent");
        }
        if (shadowRoute.type() == RouteType.AGENT_RUNTIME && shadowRoute.score() >= 0.9) {
            return ContinuationGuardResult.of(ContinuationDecision.SUSPEND_AND_ROUTE_NEW,
                    "shadow route matched high confidence runtime task");
        }
        return ContinuationGuardResult.of(ContinuationDecision.CONTINUE_CURRENT,
                "shadow route did not prove a new task");
    }

    private boolean answersRequiredInput(TaskCard taskCard, String text) {
        if (text.isBlank()) {
            return false;
        }
        for (RequiredInput input : taskCard.requiredInputs()) {
            String name = normalize(input.name());
            String description = normalize(input.description());
            String type = normalize(input.type());
            if (containsAny(name + " " + description, "invoice", "发票", "票号", "invoiceNo", "invoice_number")
                    && containsAny(text, "发票", "票号", "号码", "no", "编号")
                    && containsDigit(text)) {
                return true;
            }
            if (containsAny(name + " " + description, "amount", "金额", "money")
                    && (containsAny(text, "金额", "元", "块") || containsDigit(text))) {
                return true;
            }
            if (containsAny(name + " " + description + " " + type, "image", "document", "attachment", "图片", "附件", "影像", "发票")
                    && containsAny(text, "上传", "图片", "附件", "发票", "照片")) {
                return true;
            }
            if (!name.isBlank() && text.contains(name)) {
                return true;
            }
        }
        return taskCard.requiredInputs().isEmpty()
                && containsAny(text, "发票号", "票号", "金额", "附件", "上传", "报销单", "继续报销");
    }

    private boolean expectsAttachment(TaskCard taskCard) {
        for (RequiredInput input : taskCard.requiredInputs()) {
            String combined = normalize(input.name() + " " + input.description() + " " + input.type());
            if (containsAny(combined, "image", "document", "attachment", "图片", "附件", "影像", "发票")) {
                return true;
            }
        }
        return true;
    }

    private boolean isExplicitResume(String text) {
        return containsAny(text, "继续报销", "继续刚才", "刚才的报销", "继续处理", "还是刚才", "继续这个");
    }

    private boolean isExplicitSwitch(String text) {
        return containsAny(text, "先放一放", "换个问题", "另外", "另一个", "先帮我", "改问", "不管这个");
    }

    private boolean isClearlyNewTask(String text) {
        return containsAny(text, "今天的日程", "日程", "会议", "查预算", "预算", "天气", "新闻", "股票", "看一下今天");
    }

    private boolean asksAboutCurrentTask(String text) {
        return containsAny(text, "什么意思", "为什么", "怎么提供", "如何提供", "没有发票", "没有图片", "刚才需要什么", "报销进度");
    }

    private boolean containsDigit(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.isDigit(text.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String confirmationQuestion(TaskCard taskCard) {
        if (taskCard.confirmationQuestion() != null && !taskCard.confirmationQuestion().isBlank()) {
            return taskCard.confirmationQuestion();
        }
        return DEFAULT_CONFIRMATION;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
