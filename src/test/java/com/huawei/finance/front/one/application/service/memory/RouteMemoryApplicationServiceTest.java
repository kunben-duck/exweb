package com.huawei.finance.front.one.application.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.huawei.finance.front.one.application.config.RouteMemoryProperties;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.RouteMemoryRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.RouteMemoryContext;
import com.huawei.finance.front.one.domain.memory.RouteMemoryItem;
import com.huawei.finance.front.one.domain.memory.RouteMemoryItemStatus;
import com.huawei.finance.front.one.domain.memory.RouteMemoryItemType;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RouteMemoryApplicationServiceTest {
    private final UserContext user = new UserContext("tenant1", "user1", "tester");
    private final InMemoryRouteMemoryRepository repository = new InMemoryRouteMemoryRepository();
    private final RouteMemoryApplicationService service = new RouteMemoryApplicationService(
            repository, new FixedIdGenerator(), new RouteMemoryProperties());

    @Test
    void loadsRecentRoutesAndActiveClarificationsAsIntentHistory() {
        service.appendRoute(new RouteMemoryApplicationService.RouteMemoryRouteCommand(
                user, "session1", "run1", "支付成功率口径",
                intent("intent_a", "财经知识助手"),
                RouteTarget.domainAgent("intent_a", "intent-agent", 1.0, "accepted")));
        service.appendClarification(user, "session1", "run2", "interaction1", Map.of(
                "originalQuery", "看下方案",
                "clarifyQuestion", "你想看处理方案还是项目方案？",
                "clarificationType", "AMBIGUOUS_ROUTE"));

        RouteMemoryContext context = service.loadForIntent(user, "session1",
                "clarify_answer", Map.of());

        assertThat(context.routeTrigger()).isEqualTo("clarify_answer");
        assertThat(context.history()).containsExactly(
                Map.of("type", "route", "query", "支付成功率口径", "intent", "财经知识助手"),
                Map.of("type", "clarify", "query", "看下方案",
                        "clarifyQuestion", "你想看处理方案还是项目方案？",
                        "clarificationType", "AMBIGUOUS_ROUTE"));
    }

    @Test
    void foldsActiveClarifications() {
        service.appendClarification(user, "session1", "run2", "interaction1", Map.of(
                "originalQuery", "看下方案",
                "clarifyQuestion", "你想看处理方案还是项目方案？"));

        service.foldActiveClarifications(user, "session1");

        assertThat(repository.findActiveClarifications("tenant1", "user1", "session1")).isEmpty();
        assertThat(repository.items).allMatch(item -> item.status() == RouteMemoryItemStatus.FOLDED);
    }

    @Test
    void completeRouteFoldsClarificationsAndAppendsRouteInOneWriteTask() {
        service.appendClarification(user, "session1", "run2", "interaction1", Map.of(
                "originalQuery", "看下方案",
                "clarifyQuestion", "你想看处理方案还是项目方案？"));

        service.completeRoute(new RouteMemoryApplicationService.RouteMemoryRouteCommand(
                user, "session1", "run3", "用户澄清后的问题",
                intent("intent_b", "财经问数"),
                RouteTarget.domainAgent("skill_b", "intent-agent", 1.0, "accepted")));

        assertThat(repository.operations).containsSubsequence("fold", "save:ROUTE");
        assertThat(repository.findActiveClarifications("tenant1", "user1", "session1")).isEmpty();
        RouteMemoryItem route = repository.items.stream()
                .filter(item -> item.itemType() == RouteMemoryItemType.ROUTE)
                .findFirst()
                .orElseThrow();
        assertThat(route.intentId()).isEqualTo("intent_b");
        assertThat(route.domainAgentId()).isEqualTo("skill_b");
    }

    @Test
    void completeRouteCanRecordRelayNoMatchRoute() {
        IntentDecision relayIntent = new IntentDecision("relay", "no_match", TaskComplexity.COMPLEX, 0.0,
                false, null, Map.of("routeAction", "ROUTE_MULTI"), List.of(), Map.of());

        service.completeRoute(new RouteMemoryApplicationService.RouteMemoryRouteCommand(
                user, "session1", "run-relay", "复杂任务问题",
                relayIntent,
                RouteTarget.agentRuntime("intent-agent", 0.0, "route to relay")));

        RouteMemoryItem route = repository.items.stream()
                .filter(item -> item.itemType() == RouteMemoryItemType.ROUTE)
                .findFirst()
                .orElseThrow();
        assertThat(route.intentId()).isEqualTo("relay");
        assertThat(route.intentName()).isEqualTo("no_match");
        assertThat(route.domainAgentId()).isNull();
        assertThat(route.payload())
                .containsEntry("targetProvider", "relay")
                .containsEntry("routeAction", "ROUTE_MULTI");
        assertThat(service.latestRouteIsRelayFallback(user, "session1")).isTrue();

        RouteMemoryContext context = service.loadForIntent(user, "session1",
                "fallback_followup", Map.of());
        assertThat(context.history()).containsExactly(
                Map.of("type", "route", "query", "复杂任务问题", "intent", "no_match"));
    }

    @Test
    void completeWithoutRouteOnlyFoldsClarifications() {
        service.appendClarification(user, "session1", "run2", "interaction1", Map.of(
                "originalQuery", "看下方案",
                "clarifyQuestion", "你想看处理方案还是项目方案？"));

        service.completeWithoutRoute(user, "session1");

        assertThat(repository.operations).contains("fold");
        assertThat(repository.items).noneMatch(item -> item.itemType() == RouteMemoryItemType.ROUTE);
        assertThat(repository.findActiveClarifications("tenant1", "user1", "session1")).isEmpty();
    }

    @Test
    void readFailuresFallbackToEmptyContext() {
        RouteMemoryApplicationService failingService = new RouteMemoryApplicationService(
                new FailingRouteMemoryRepository(), new FixedIdGenerator(), new RouteMemoryProperties());

        RouteMemoryContext context = failingService.loadForIntent(user, "session1",
                "domain_reject", Map.of("lastIntent", "A"));

        assertThat(context.routeTrigger()).isEqualTo("domain_reject");
        assertThat(context.lastIntentRejectReason()).containsEntry("lastIntent", "A");
        assertThat(context.history()).isEmpty();
        assertThat(failingService.activeClarificationCount(user, "session1")).isZero();
    }

    @Test
    void readCircuitBreakerSkipsRepositoryAfterThreshold() {
        RouteMemoryProperties properties = new RouteMemoryProperties();
        properties.setReadTimeout(Duration.ofMillis(50));
        RouteMemoryProperties.CircuitBreaker circuitBreaker = new RouteMemoryProperties.CircuitBreaker();
        circuitBreaker.setFailureThreshold(2);
        circuitBreaker.setOpenDuration(Duration.ofSeconds(10));
        properties.setCircuitBreaker(circuitBreaker);
        CountingFailingRouteMemoryRepository failingRepository = new CountingFailingRouteMemoryRepository();
        RouteMemoryApplicationService failingService = new RouteMemoryApplicationService(
                failingRepository, new FixedIdGenerator(), properties);

        failingService.loadForIntent(user, "session1", "first_turn", Map.of());
        failingService.loadForIntent(user, "session1", "first_turn", Map.of());
        failingService.loadForIntent(user, "session1", "first_turn", Map.of());

        assertThat(failingRepository.readCount()).isEqualTo(2);
    }

    @Test
    void writeFailuresDoNotPropagateToChatRunFlow() {
        RouteMemoryApplicationService failingService = new RouteMemoryApplicationService(
                new FailingRouteMemoryRepository(), new FixedIdGenerator(), new RouteMemoryProperties());

        assertThatCode(() -> failingService.appendRoute(new RouteMemoryApplicationService.RouteMemoryRouteCommand(
                user, "session1", "run1", "query", null,
                RouteTarget.domainAgent("agent_a", "intent-agent", 1.0, "ok"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> failingService.appendClarification(user, "session1", "run2", "interaction1",
                Map.of("originalQuery", "query", "clarifyQuestion", "question")))
                .doesNotThrowAnyException();
        assertThatCode(() -> failingService.foldActiveClarifications(user, "session1"))
                .doesNotThrowAnyException();
    }

    private IntentDecision intent(String code, String name) {
        return new IntentDecision(code, name, TaskComplexity.SIMPLE, 0.9,
                true, code, Map.of(), List.of(), Map.of());
    }

    private static class FixedIdGenerator implements IdGenerator {
        private int sequence;

        @Override
        public String newId(String prefix, IdGenerateContext context) {
            sequence++;
            return prefix + "_" + sequence;
        }
    }

    private static class InMemoryRouteMemoryRepository implements RouteMemoryRepository {
        private final List<RouteMemoryItem> items = new ArrayList<>();
        private final List<String> operations = new ArrayList<>();

        @Override
        public RouteMemoryItem save(RouteMemoryItem item) {
            operations.add("save:" + item.itemType().name());
            items.add(item);
            return item;
        }

        @Override
        public List<RouteMemoryItem> findRecentRoutes(String tenantId, String userId, String sessionId, int limit) {
            return items.stream()
                    .filter(item -> ownerMatches(item, tenantId, userId, sessionId))
                    .filter(item -> item.itemType() == RouteMemoryItemType.ROUTE)
                    .filter(item -> item.status() == RouteMemoryItemStatus.ACTIVE)
                    .sorted(Comparator.comparing(RouteMemoryItem::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<RouteMemoryItem> findActiveClarifications(String tenantId, String userId, String sessionId) {
            return items.stream()
                    .filter(item -> ownerMatches(item, tenantId, userId, sessionId))
                    .filter(item -> item.itemType() == RouteMemoryItemType.CLARIFY)
                    .filter(item -> item.status() == RouteMemoryItemStatus.ACTIVE)
                    .sorted(Comparator.comparing(RouteMemoryItem::createdAt))
                    .toList();
        }

        @Override
        public int foldActiveClarifications(String tenantId, String userId, String sessionId, Instant foldedAt) {
            operations.add("fold");
            List<RouteMemoryItem> folded = new ArrayList<>();
            int count = 0;
            for (RouteMemoryItem item : items) {
                if (ownerMatches(item, tenantId, userId, sessionId)
                        && item.itemType() == RouteMemoryItemType.CLARIFY
                        && item.status() == RouteMemoryItemStatus.ACTIVE) {
                    folded.add(new RouteMemoryItem(item.id(), item.tenantId(), item.userId(), item.sessionId(),
                            item.itemType(), RouteMemoryItemStatus.FOLDED, item.queryText(), item.intentId(),
                            item.intentName(), item.domainAgentId(), item.routeSource(), item.clarifyQuestion(),
                            item.clarificationType(), item.sourceRunId(), item.interactionId(), item.payload(),
                            foldedAt, item.createdAt(), foldedAt));
                    count++;
                } else {
                    folded.add(item);
                }
            }
            items.clear();
            items.addAll(folded);
            return count;
        }

        private boolean ownerMatches(RouteMemoryItem item, String tenantId, String userId, String sessionId) {
            return tenantId.equals(item.tenantId())
                    && userId.equals(item.userId())
                    && sessionId.equals(item.sessionId());
        }
    }

    private static class FailingRouteMemoryRepository implements RouteMemoryRepository {
        @Override
        public RouteMemoryItem save(RouteMemoryItem item) {
            throw new IllegalStateException("route memory down");
        }

        @Override
        public List<RouteMemoryItem> findRecentRoutes(String tenantId, String userId, String sessionId, int limit) {
            throw new IllegalStateException("route memory down");
        }

        @Override
        public List<RouteMemoryItem> findActiveClarifications(String tenantId, String userId, String sessionId) {
            throw new IllegalStateException("route memory down");
        }

        @Override
        public int foldActiveClarifications(String tenantId, String userId, String sessionId, Instant foldedAt) {
            throw new IllegalStateException("route memory down");
        }
    }

    private static final class CountingFailingRouteMemoryRepository extends FailingRouteMemoryRepository {
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public List<RouteMemoryItem> findRecentRoutes(String tenantId, String userId, String sessionId, int limit) {
            reads.incrementAndGet();
            return super.findRecentRoutes(tenantId, userId, sessionId, limit);
        }

        int readCount() {
            return reads.get();
        }
    }
}
