package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.agent.AgentBindingStatus;
import com.huawei.finance.front.one.domain.agent.AgentBindingType;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.task.ContinuationDecision;
import com.huawei.finance.front.one.domain.task.TaskCard;
import com.huawei.finance.front.one.domain.task.TaskStatus;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * active task 编排服务。
 *
 * <p>该服务把“存在 active binding”拆成更细的任务决策：SubAgent 绑定必须先找到 TaskCard，
 * 再经过 ContinuationGuard 判断是否续接；只有复杂 AgentRuntime 绑定仍然允许直接续接，因为
 * AgentRuntime 本身负责复杂任务规划和上下文管理。</p>
 */
@Service
public class TaskOrchestrationApplicationService {
    private final ContinuationGuard continuationGuard;
    private final TaskCardApplicationService taskCardService;
    private final AgentBindingApplicationService bindingService;

    public TaskOrchestrationApplicationService(ContinuationGuard continuationGuard, TaskCardApplicationService taskCardService,
                                               AgentBindingApplicationService bindingService) {
        this.continuationGuard = continuationGuard;
        this.taskCardService = taskCardService;
        this.bindingService = bindingService;
    }

    /**
     * 解析 active binding 对本轮输入的处理方式。
     *
     * @param binding 当前 active binding。
     * @param command 本轮聊天命令。
     * @param runId 本轮运行标识。
     * @param shadowRouteSupplier shadow route 供应器，只有模糊场景才会调用。
     * @return active task 编排结果；为空表示没有可使用的 active task。
     */
    public Optional<ActiveTaskResolution> resolveActive(AgentBinding binding, ChatCommand command, String runId,
                                                        Supplier<RouteTarget> shadowRouteSupplier) {
        if (binding == null) {
            return Optional.empty();
        }
        if (binding.bindingType() == AgentBindingType.AGENT_RUNTIME) {
            AgentBinding touched = bindingService.touchForRun(binding, runId);
            return Optional.of(new ActiveTaskResolution(ContinuationDecision.CONTINUE_CURRENT,
                    routeFromBinding(touched), touched, null, false));
        }

        Optional<TaskCard> activeTask = taskCardService.findActive(binding.tenantId(), binding.userId(), binding.chatSessionId());
        if (activeTask.isEmpty()) {
            bindingService.updateStatus(binding, AgentBindingStatus.CANCELLED);
            return Optional.of(ActiveTaskResolution.routeNew(ContinuationDecision.ROUTE_NEW));
        }

        TaskCard taskCard = activeTask.get();
        ContinuationGuardResult guardResult = continuationGuard.evaluate(taskCard, command, null);
        if (guardResult.decision() == ContinuationDecision.ASK_USER_CONFIRMATION && shadowRouteSupplier != null) {
            RouteTarget shadowRoute = shadowRouteSupplier.get();
            guardResult = continuationGuard.evaluate(taskCard, command, shadowRoute);
        }
        return Optional.of(applyDecision(binding, taskCard, command, runId, guardResult));
    }

    private ActiveTaskResolution applyDecision(AgentBinding binding, TaskCard taskCard, ChatCommand command,
                                               String runId, ContinuationGuardResult guardResult) {
        Map<String, Object> payload = Map.of("reason", guardResult.reason());
        return switch (guardResult.decision()) {
            case CONTINUE_CURRENT, RESUME_SUSPENDED -> {
                TaskCard touchedTask = taskCardService.touch(taskCard, runId);
                AgentBinding touchedBinding = bindingService.touchForRun(binding, runId);
                yield new ActiveTaskResolution(guardResult.decision(), routeFromBinding(touchedBinding),
                        touchedBinding, touchedTask, false);
            }
            case CANCEL_CURRENT -> {
                taskCardService.transition(taskCard, TaskStatus.CANCELLED, TaskStatus.CANCELLED, runId,
                        "TASK_CANCELLED_BY_USER", payload);
                bindingService.updateStatus(binding, AgentBindingStatus.CANCELLED);
                RouteTarget route = RouteTarget.systemResponse("已取消刚才的报销任务。");
                yield new ActiveTaskResolution(guardResult.decision(), route, null, null, false);
            }
            case SUSPEND_AND_ROUTE_NEW -> {
                taskCardService.transition(taskCard, TaskStatus.SUSPENDED, TaskStatus.SUSPENDED, runId,
                        "TASK_SUSPENDED_FOR_NEW_ROUTE", payload);
                bindingService.updateStatus(binding, AgentBindingStatus.SUSPENDED);
                yield ActiveTaskResolution.routeNew(guardResult.decision());
            }
            case ASK_USER_CONFIRMATION -> {
                String question = guardResult.confirmationQuestion() == null || guardResult.confirmationQuestion().isBlank()
                        ? "你是要继续处理刚才的报销单，还是开始新的任务？"
                        : guardResult.confirmationQuestion();
                TaskCard waitingTask = taskCardService.transition(taskCard, TaskStatus.WAITING_USER_CONFIRMATION,
                        TaskStatus.UNKNOWN, runId, "TASK_WAITING_USER_CONFIRMATION",
                        Map.of("reason", guardResult.reason(), "question", question));
                AgentBinding waitingBinding = bindingService.updateStatus(binding, AgentBindingStatus.WAITING_USER_CONFIRMATION);
                yield new ActiveTaskResolution(guardResult.decision(), RouteTarget.systemResponse(question),
                        waitingBinding, waitingTask, false);
            }
            case ROUTE_NEW -> ActiveTaskResolution.routeNew(ContinuationDecision.ROUTE_NEW);
        };
    }

    private RouteTarget routeFromBinding(AgentBinding binding) {
        if (binding.bindingType() == AgentBindingType.SUB_AGENT) {
            return RouteTarget.subAgent(binding.agentCode(), "agent-binding", 1.0, "active task continuation");
        }
        return RouteTarget.agentRuntime("agent-binding", 1.0, "active runtime binding");
    }
}
