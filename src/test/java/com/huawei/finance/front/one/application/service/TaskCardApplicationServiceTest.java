package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.task.TaskCardCache;
import com.huawei.finance.front.one.application.integration.task.TaskCardRepository;
import com.huawei.finance.front.one.application.integration.task.TaskEventRepository;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.agent.AgentBindingStatus;
import com.huawei.finance.front.one.domain.agent.AgentBindingType;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.task.TaskCard;
import com.huawei.finance.front.one.domain.task.TaskEvent;
import com.huawei.finance.front.one.domain.task.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskCardApplicationServiceTest {
    @Test
    void readsCacheBeforeRepository() {
        FakeRepository repository = new FakeRepository();
        FakeCache cache = new FakeCache();
        TaskCard task = task(TaskStatus.ACTIVE);
        cache.put(task);
        TaskCardApplicationService service = service(repository, cache, new FakeEventRepository());

        Optional<TaskCard> found = service.findActive("t", "u", "s");

        assertThat(found).contains(task);
        assertThat(repository.findActiveCalls).isZero();
    }

    @Test
    void missFallsBackToRepositoryAndWarmsCache() {
        FakeRepository repository = new FakeRepository();
        FakeCache cache = new FakeCache();
        TaskCard task = task(TaskStatus.ACTIVE);
        repository.saved = task;
        TaskCardApplicationService service = service(repository, cache, new FakeEventRepository());

        Optional<TaskCard> found = service.findActive("t", "u", "s");

        assertThat(found).contains(task);
        assertThat(cache.getActive("t", "u", "s")).contains(task);
    }

    @Test
    void observesCompletedEventAndReleasesActiveCache() {
        FakeRepository repository = new FakeRepository();
        FakeCache cache = new FakeCache();
        FakeEventRepository events = new FakeEventRepository();
        TaskCard task = task(TaskStatus.REQUIRES_USER_INPUT);
        repository.saved = task;
        cache.put(task);
        TaskCardApplicationService service = service(repository, cache, events);

        boolean observed = service.observeEvent(task, MessageCompletedEvent.of("run", "s", Map.of("taskStatus", "COMPLETED")));

        assertThat(observed).isTrue();
        assertThat(repository.saved.taskStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(cache.getActive("t", "u", "s")).isEmpty();
        assertThat(events.saved.toStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void createForSubAgentUsesEmployeeReimbursementDefaults() {
        FakeRepository repository = new FakeRepository();
        FakeCache cache = new FakeCache();
        TaskCardApplicationService service = service(repository, cache, new FakeEventRepository());

        TaskCard task = service.createForSubAgent(binding(), RouteTarget.subAgent("employee_reimbursement_agent", "intent-service", 0.91, "matched"), "run");

        assertThat(task.taskDomain()).isEqualTo("employee_reimbursement");
        assertThat(task.taskGoal()).contains("员工报销");
        assertThat(cache.getActive("t", "u", "s")).contains(task);
    }

    private TaskCardApplicationService service(FakeRepository repository, FakeCache cache, FakeEventRepository eventRepository) {
        return new TaskCardApplicationService(repository, cache, eventRepository, new FakeIdGenerator(), Duration.ofDays(3));
    }

    private AgentBinding binding() {
        Instant now = Instant.now();
        return new AgentBinding("binding1", "t", "u", "s", AgentBindingType.SUB_AGENT,
                "employee_reimbursement_agent", null, null, null, AgentBindingStatus.ACTIVE,
                "run", now.plus(Duration.ofDays(1)), now, now, Map.of());
    }

    private TaskCard task(TaskStatus status) {
        Instant now = Instant.now();
        return new TaskCard("task1", "t", "u", "s", "binding1", "创建并推进员工报销单",
                "employee_reimbursement", "employee_reimbursement_agent", null, status, status,
                java.util.List.of(), Map.of(), null, null, now.plus(Duration.ofDays(1)), now, now, Map.of());
    }

    private static class FakeRepository implements TaskCardRepository {
        private TaskCard saved;
        private int findActiveCalls;

        @Override
        public Optional<TaskCard> findActive(String tenantId, String userId, String sessionId) {
            findActiveCalls++;
            return Optional.ofNullable(saved);
        }

        @Override
        public Optional<TaskCard> findByTaskId(String tenantId, String userId, String sessionId, String taskId) {
            return Optional.ofNullable(saved).filter(task -> task.taskId().equals(taskId));
        }

        @Override
        public TaskCard save(TaskCard taskCard) {
            saved = taskCard;
            return taskCard;
        }
    }

    private static class FakeCache implements TaskCardCache {
        private final Map<String, TaskCard> active = new HashMap<>();

        @Override
        public Optional<TaskCard> getActive(String tenantId, String userId, String sessionId) {
            return Optional.ofNullable(active.get(key(tenantId, userId, sessionId)));
        }

        @Override
        public void put(TaskCard taskCard) {
            if (taskCard.activeAt(Instant.now())) {
                active.put(key(taskCard.tenantId(), taskCard.userId(), taskCard.chatSessionId()), taskCard);
            } else {
                evictActive(taskCard.tenantId(), taskCard.userId(), taskCard.chatSessionId());
            }
        }

        @Override
        public void evictActive(String tenantId, String userId, String sessionId) {
            active.remove(key(tenantId, userId, sessionId));
        }

        private String key(String tenantId, String userId, String sessionId) {
            return tenantId + ":" + userId + ":" + sessionId;
        }
    }

    private static class FakeEventRepository implements TaskEventRepository {
        private TaskEvent saved;

        @Override
        public void save(TaskEvent event) {
            saved = event;
        }
    }

    private static class FakeIdGenerator implements IdGenerator {
        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_1";
        }
    }
}
