package com.huawei.it.ex.one.application.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.huawei.it.ex.one.application.config.IntentRecordProperties;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.IntentRecognitionRecord;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.routing.RouteTarget;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

class IntentRecognitionRecordServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdGenerator idGenerator = new FixedIdGenerator();

    @Test
    void doesNotSubmitTaskWhenRecordDisabled() {
        CapturingRepository repository = new CapturingRepository();
        IntentRecordProperties properties = new IntentRecordProperties();
        properties.setEnabled(false);
        CountingExecutor executor = new CountingExecutor();
        IntentRecognitionRecordService service = new IntentRecognitionRecordService(
                properties, repository, idGenerator, objectMapper, executor);

        service.recordAsync(snapshot(successfulIntent(0.95),
                RouteTarget.domainAgent("FIN-SKL-88888888", "intent-agent", 0.95, "accepted"), 12L));

        assertThat(executor.count).isZero();
        assertThat(repository.records).isEmpty();
    }

    @Test
    void writesAcceptedIntentRecordAsBestEffortSnapshot() {
        CapturingRepository repository = new CapturingRepository();
        IntentRecognitionRecordService service = new IntentRecognitionRecordService(
                enabledProperties(), repository, idGenerator, objectMapper, Runnable::run);

        service.recordAsync(snapshot(successfulIntent(0.95),
                RouteTarget.domainAgent("FIN-SKL-88888888", "intent-agent", 0.95, "accepted"), 37L));

        assertThat(repository.records).hasSize(1);
        IntentRecognitionRecord record = repository.records.get(0);
        assertThat(record.id()).isEqualTo("intentrec_1");
        assertThat(record.tenantId()).isEqualTo("tenant1");
        assertThat(record.userId()).isEqualTo("user1");
        assertThat(record.sessionId()).isEqualTo("session1");
        assertThat(record.runId()).isEqualTo("run1");
        assertThat(record.commandId()).isEqualTo("cmd1");
        assertThat(record.queryText()).isEqualTo("今年库户的总利润是多少");
        assertThat(record.queryHash()).hasSize(64);
        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.intentId()).isEqualTo("98989898dffd888df88789");
        assertThat(record.intentName()).isEqualTo("财经智能问答");
        assertThat(record.resourceId()).isEqualTo("FIN-SKL-88888888");
        assertThat(record.confidence()).isEqualTo(0.95);
        assertThat(record.source()).isEqualTo("llm");
        assertThat(record.candidateCount()).isEqualTo(1);
        assertThat(record.confidenceThreshold()).isEqualTo(0.85);
        assertThat(record.accepted()).isTrue();
        assertThat(record.routeType()).isEqualTo("DOMAIN_AGENT");
        assertThat(record.routeAgentCode()).isEqualTo("FIN-SKL-88888888");
        assertThat(record.resultMessage()).contains("匹配成功");
        assertThat(record.itemsJson()).contains("ex_FIN-SKL-88888888");
        assertThat(record.rawResponseJson()).contains("\"code\":200");
        assertThat(record.errorMessage()).isNull();
        assertThat(record.latencyMs()).isEqualTo(37L);
    }

    @Test
    void recordsLowConfidenceAsNotAccepted() {
        CapturingRepository repository = new CapturingRepository();
        IntentRecognitionRecordService service = new IntentRecognitionRecordService(
                enabledProperties(), repository, idGenerator, objectMapper, Runnable::run);

        service.recordAsync(snapshot(successfulIntent(0.70),
                RouteTarget.agentRuntime("intent-agent", 0.70, "low confidence"), 9L));

        assertThat(repository.records).hasSize(1);
        IntentRecognitionRecord record = repository.records.get(0);
        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.accepted()).isFalse();
        assertThat(record.routeType()).isEqualTo("AGENT_RUNTIME");
    }

    @Test
    void recordsDegradedIntentWithoutThrowing() {
        CapturingRepository repository = new CapturingRepository();
        IntentRecognitionRecordService service = new IntentRecognitionRecordService(
                enabledProperties(), repository, idGenerator, objectMapper, Runnable::run);
        IntentDecision degraded = new IntentDecision(
                "finance.runtime.degraded",
                "意图服务不可用，转入 AgentRuntime",
                TaskComplexity.COMPLEX,
                0.0,
                false,
                null,
                Map.of(),
                List.of(),
                Map.of("source", "http-intent-degraded", "reason", "timeout")
        );

        service.recordAsync(snapshot(degraded,
                RouteTarget.agentRuntime("intent-agent", 0.0, "degraded"), 5L));

        assertThat(repository.records).hasSize(1);
        IntentRecognitionRecord record = repository.records.get(0);
        assertThat(record.status()).isEqualTo("DEGRADED");
        assertThat(record.errorMessage()).isEqualTo("timeout");
    }

    @Test
    void repositoryFailureDoesNotEscapeMainFlow() {
        IntentRecognitionRecordService service = new IntentRecognitionRecordService(
                enabledProperties(), record -> {
                    throw new IllegalStateException("db down");
                }, idGenerator, objectMapper, Runnable::run);

        assertThatCode(() -> service.recordAsync(snapshot(successfulIntent(0.95),
                RouteTarget.domainAgent("FIN-SKL-88888888", "intent-agent", 0.95, "accepted"), 12L)))
                .doesNotThrowAnyException();
    }

    @Test
    void executorRejectionDoesNotEscapeMainFlow() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        IntentRecognitionRecordService service = new IntentRecognitionRecordService(
                enabledProperties(), new CapturingRepository(), idGenerator, objectMapper, rejectingExecutor);

        assertThatCode(() -> service.recordAsync(snapshot(successfulIntent(0.95),
                RouteTarget.domainAgent("FIN-SKL-88888888", "intent-agent", 0.95, "accepted"), 12L)))
                .doesNotThrowAnyException();
    }

    private IntentRecognitionRecordSnapshot snapshot(IntentDecision decision, RouteTarget route, Long latencyMs) {
        return IntentRecognitionRecordSnapshot.of(new IntentRecognitionRecordSnapshot.IntentRecognitionRecordInput(
                user(), command(), "run1", decision, route, 0.85, latencyMs));
    }

    private IntentRecordProperties enabledProperties() {
        IntentRecordProperties properties = new IntentRecordProperties();
        properties.setEnabled(true);
        properties.setMaxQueryLength(4096);
        properties.setMaxRawJsonLength(65536);
        return properties;
    }

    private IntentDecision successfulIntent(double confidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("confidence", confidence);
        item.put("intentId", "98989898dffd888df88789");
        item.put("intentName", "财经智能问答");
        item.put("accessName", "ex_FIN-SKL-88888888");
        item.put("resourceInstruction", Map.of("resourceId", "FIN-SKL-88888888"));
        item.put("score", null);
        item.put("source", "llm");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", List.of(item));
        result.put("message", "[识别结果]匹配成功: 财经智能问答;");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("data", Map.of("status", "success", "message", "success", "result", result));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source", "http-intent-service");
        raw.put("reason", "intent response parsed");
        raw.put("response", response);
        raw.put("selectedItem", item);
        raw.put("resultMessage", result.get("message"));
        return new IntentDecision(
                "98989898dffd888df88789",
                "财经智能问答",
                TaskComplexity.SIMPLE,
                confidence,
                true,
                "FIN-SKL-88888888",
                Map.of("resourceId", "FIN-SKL-88888888", "source", "llm"),
                List.of(),
                raw
        );
    }

    private ChatCommand command() {
        return new ChatCommand("cmd1", "tenant1", "user1", "session1", null, "web",
                "今年库户的总利润是多少", List.of(), Map.of());
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "Alice");
    }

    private static class CapturingRepository implements com.huawei.it.ex.one.application.integration.intent.IntentRecognitionRecordRepository {
        private final List<IntentRecognitionRecord> records = new ArrayList<>();

        @Override
        public void save(IntentRecognitionRecord record) {
            records.add(record);
        }
    }

    private static class CountingExecutor implements Executor {
        private int count;

        @Override
        public void execute(Runnable command) {
            count++;
            command.run();
        }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_1";
        }
    }
}
