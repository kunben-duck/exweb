package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.task.TaskCardCache;
import com.huawei.finance.front.one.application.integration.task.TaskCardRepository;
import com.huawei.finance.front.one.application.integration.task.TaskEventRepository;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.task.BusinessObjectRef;
import com.huawei.finance.front.one.domain.task.RequiredInput;
import com.huawei.finance.front.one.domain.task.SubAgentTaskResult;
import com.huawei.finance.front.one.domain.task.TaskCard;
import com.huawei.finance.front.one.domain.task.TaskEvent;
import com.huawei.finance.front.one.domain.task.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TaskCard 应用服务。
 *
 * <p>本服务统一管理 SubAgent 任务的创建、续期、状态迁移、Redis 回源和任务事件审计。
 * 关键约束是：openGauss 为事实源，Redis 只是热缓存；因此所有状态变更都先保存 TaskCard，
 * 再刷新或删除 Redis active key。</p>
 */
@Service
public class TaskCardApplicationService {
    private static final String EMPLOYEE_REIMBURSEMENT_AGENT = "employee_reimbursement_agent";
    private static final String EMPLOYEE_REIMBURSEMENT_DOMAIN = "employee_reimbursement";
    private static final String EMPLOYEE_REIMBURSEMENT_GOAL = "创建并推进员工报销单";

    private final TaskCardRepository repository;
    private final TaskCardCache cache;
    private final TaskEventRepository eventRepository;
    private final IdGenerator idGenerator;
    private final Duration ttl;

    public TaskCardApplicationService(TaskCardRepository repository, TaskCardCache cache, TaskEventRepository eventRepository,
                                      IdGenerator idGenerator, @Value("${financeex.task.ttl:3d}") Duration ttl) {
        this.repository = repository;
        this.cache = cache;
        this.eventRepository = eventRepository;
        this.idGenerator = idGenerator;
        this.ttl = ttl == null ? Duration.ofDays(3) : ttl;
    }

    /**
     * 查询当前会话 active task，优先 Redis，miss 后回源 openGauss 并回填 Redis。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前可续接任务。
     */
    public Optional<TaskCard> findActive(String tenantId, String userId, String sessionId) {
        Instant now = Instant.now();
        Optional<TaskCard> cached = cache.getActive(tenantId, userId, sessionId).filter(task -> task.activeAt(now));
        if (cached.isPresent()) {
            return cached;
        }
        Optional<TaskCard> persisted = repository.findActive(tenantId, userId, sessionId).filter(task -> task.activeAt(now));
        persisted.ifPresent(cache::put);
        return persisted;
    }

    /**
     * 为一次 SubAgent 路由创建任务卡片。
     *
     * @param binding 与任务对应的路由绑定。
     * @param route 本轮路由结果。
     * @param runId 本轮运行标识。
     * @return 已持久化的任务卡片。
     */
    public TaskCard createForSubAgent(AgentBinding binding, RouteTarget route, String runId) {
        Instant now = Instant.now();
        String taskId = idGenerator.newId("task", IdGenerateContext.of(binding.tenantId(), binding.userId(), binding.chatSessionId()));
        String agentCode = route.selectedAgentCode();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("routeSource", route.routeSource());
        metadata.put("routeScore", route.score());
        metadata.put("routeReason", route.reason());
        TaskCard taskCard = new TaskCard(
                taskId,
                binding.tenantId(),
                binding.userId(),
                binding.chatSessionId(),
                binding.id(),
                taskGoal(agentCode),
                taskDomain(agentCode),
                agentCode,
                binding.agentSessionId(),
                TaskStatus.ACTIVE,
                TaskStatus.ACTIVE,
                List.of(),
                Map.of(),
                null,
                null,
                expiresAt(),
                now,
                now,
                metadata
        );
        TaskCard saved = save(taskCard);
        appendEvent(saved, runId, "TASK_CREATED", null, saved.taskStatus(), metadata);
        return saved;
    }

    /**
     * 刷新任务过期时间，表示用户本轮输入确认属于该任务。
     *
     * @param taskCard 当前任务。
     * @param runId 本轮运行标识。
     * @return 续期后的任务。
     */
    public TaskCard touch(TaskCard taskCard, String runId) {
        TaskCard touched = taskCard.withExpiry(expiresAt(), Instant.now());
        TaskCard saved = save(touched);
        appendEvent(saved, runId, "TASK_TOUCHED", taskCard.taskStatus(), saved.taskStatus(), Map.of());
        return saved;
    }

    /**
     * 按指定状态迁移任务并记录事件。
     *
     * @param taskCard 当前任务。
     * @param nextStatus 新任务状态。
     * @param rawStatus 标准化器原始识别状态。
     * @param runId 本轮运行标识。
     * @param eventType 任务事件类型。
     * @param payload 附加诊断信息。
     * @return 迁移后的任务。
     */
    public TaskCard transition(TaskCard taskCard, TaskStatus nextStatus, TaskStatus rawStatus, String runId,
                               String eventType, Map<String, Object> payload) {
        TaskCard next = taskCard.withStatus(nextStatus, rawStatus == null ? nextStatus : rawStatus, Instant.now());
        TaskCard saved = save(next);
        appendEvent(saved, runId, eventType, taskCard.taskStatus(), saved.taskStatus(), payload);
        return saved;
    }

    /**
     * 根据 SubAgent message.completed 事件更新 TaskCard。
     *
     * @param taskCard 当前任务。
     * @param event 聊天事件。
     * @return true 表示事件中包含 taskStatus 并已更新任务。
     */
    public boolean observeEvent(TaskCard taskCard, ChatEvent event) {
        if (taskCard == null || event == null || event.payload() == null || !event.payload().containsKey("taskStatus")) {
            return false;
        }
        Map<String, Object> payload = event.payload();
        SubAgentTaskResult result = new SubAgentTaskResult(
                stringValue(payload.get("message")),
                TaskStatus.from(stringValue(payload.get("taskStatus")), taskCard.taskStatus()),
                TaskStatus.from(stringValue(payload.get("rawNormalizedStatus")), TaskStatus.UNKNOWN),
                requiredInputs(payload.get("requiredInputs")),
                stringValue(payload.get("agentSessionId")),
                businessObjectRefs(payload.get("businessObjectRefs")),
                doubleValue(payload.get("confidence"), 1.0),
                stringValue(payload.get("confirmationQuestion")),
                payload
        );
        TaskCard next = taskCard.withResult(result, expiresAt(), Instant.now());
        TaskCard saved = save(next);
        appendEvent(saved, event.runId(), "TASK_STATUS_CHANGED", taskCard.taskStatus(), saved.taskStatus(), payload);
        return true;
    }

    /**
     * 下游没有返回任务状态时的保守兜底。
     *
     * @param taskCard 当前任务。
     * @param runId 本轮运行标识。
     */
    public void markWaitingConfirmationIfNoStatus(TaskCard taskCard, String runId) {
        if (taskCard == null || !taskCard.taskStatus().activeRoutable()) {
            return;
        }
        transition(taskCard, TaskStatus.WAITING_USER_CONFIRMATION, TaskStatus.UNKNOWN, runId,
                "TASK_STATUS_UNKNOWN", Map.of("reason", "subagent did not return taskStatus"));
    }

    private TaskCard save(TaskCard taskCard) {
        TaskCard saved = repository.save(taskCard);
        cache.put(saved);
        return saved;
    }

    private void appendEvent(TaskCard taskCard, String runId, String eventType, TaskStatus from, TaskStatus to,
                             Map<String, Object> payload) {
        String eventId = idGenerator.newId("task_event",
                IdGenerateContext.of(taskCard.tenantId(), taskCard.userId(), taskCard.chatSessionId()));
        eventRepository.save(new TaskEvent(eventId, taskCard.tenantId(), taskCard.userId(), taskCard.chatSessionId(),
                taskCard.taskId(), runId, eventType, from, to, payload, Instant.now()));
    }

    private Instant expiresAt() {
        return Instant.now().plus(ttl);
    }

    private String taskDomain(String agentCode) {
        if (EMPLOYEE_REIMBURSEMENT_AGENT.equals(agentCode)) {
            return EMPLOYEE_REIMBURSEMENT_DOMAIN;
        }
        return agentCode == null || agentCode.isBlank() ? "sub_agent_task" : agentCode;
    }

    private String taskGoal(String agentCode) {
        if (EMPLOYEE_REIMBURSEMENT_AGENT.equals(agentCode)) {
            return EMPLOYEE_REIMBURSEMENT_GOAL;
        }
        return "处理 " + (agentCode == null || agentCode.isBlank() ? "SubAgent" : agentCode) + " 任务";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private List<RequiredInput> requiredInputs(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<RequiredInput> inputs = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof RequiredInput input) {
                inputs.add(input);
            } else if (item instanceof Map<?, ?> map) {
                inputs.add(new RequiredInput(stringValue(map.get("name")),
                        stringValue(map.get("description")),
                        stringValue(map.get("type")),
                        requiredValue(map.get("required"))));
            }
        }
        return List.copyOf(inputs);
    }

    private List<BusinessObjectRef> businessObjectRefs(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<BusinessObjectRef> refs = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof BusinessObjectRef ref) {
                refs.add(ref);
            } else if (item instanceof Map<?, ?> map) {
                refs.add(new BusinessObjectRef(stringValue(map.get("objectType")),
                        stringValue(map.get("objectId")),
                        stringValue(map.get("displayName")),
                        safeMap(map.get("attributes"))));
            }
        }
        return List.copyOf(refs);
    }

    private Map<String, Object> safeMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        source.forEach((key, val) -> map.put(String.valueOf(key), val));
        return Map.copyOf(map);
    }

    private boolean requiredValue(Object value) {
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }
}
