package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.application.config.IntentRecordProperties;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionRecordRepository;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.IntentRecognitionRecord;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 意图识别记录异步写入服务。
 *
 * <p>该服务是统计和排障旁路。它不参与 route/run 决策，不读取 Servlet 请求上下文或企业 ThreadLocal；
 * 写入失败、线程池拒绝和序列化异常都只记录日志，不能影响聊天主链路。</p>
 */
@Service
public class IntentRecognitionRecordService {
    private static final AppLogger log = AppLoggerFactory.getLogger(IntentRecognitionRecordService.class);

    private final IntentRecordProperties properties;
    private final IntentRecognitionRecordRepository repository;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public IntentRecognitionRecordService(IntentRecordProperties properties,
                                          IntentRecognitionRecordRepository repository,
                                          IdGenerator idGenerator,
                                          ObjectMapper objectMapper,
                                          @Qualifier("intentRecognitionRecordExecutor") Executor executor) {
        this.properties = properties;
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * 异步记录一次意图识别结果。
     *
     * @param snapshot 调用方线程中构造好的不可变快照。
     */
    public void recordAsync(IntentRecognitionRecordSnapshot snapshot) {
        if (!properties.isEnabled() || snapshot == null || snapshot.intentDecision() == null) {
            return;
        }
        try {
            executor.execute(() -> writeSafely(snapshot));
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "Intent recognition record task was rejected before persistence")
                    .runId(snapshot.runId())
                    .operation("intent-record.schedule")
                    .build(), ex);
        }
    }

    private void writeSafely(IntentRecognitionRecordSnapshot snapshot) {
        try {
            repository.save(toRecord(snapshot));
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                            "Intent recognition record persistence failed")
                    .runId(snapshot.runId())
                    .operation("intent-record.write")
                    .build(), ex);
        }
    }

    private IntentRecognitionRecord toRecord(IntentRecognitionRecordSnapshot snapshot) {
        IntentDecision intent = snapshot.intentDecision();
        RouteTarget route = snapshot.routeTarget();
        Map<String, Object> raw = intent.raw();
        Object rawResponse = firstNonNull(raw.get("response"), raw);
        Object items = nested(rawResponse, "data", "result", "items");
        Object selectedItem = raw.get("selectedItem");
        String intentId = firstText(stringValue(intent.slots().get("intentId")), intent.intentCode());
        String resourceId = firstText(stringValue(intent.slots().get("resourceId")),
                stringValue(nested(selectedItem, "resourceInstruction", "resourceId")));
        String source = firstText(stringValue(intent.slots().get("source")), stringValue(nested(selectedItem, "source")));
        String status = status(intent);
        boolean accepted = accepted(intent, route);
        String id = idGenerator.newId("intentrec", IdGenerateContext.of(
                snapshot.tenantId(), snapshot.userId(), snapshot.sessionId(), snapshot.runId()));
        String query = truncate(snapshot.queryText(), properties.normalizedMaxQueryLength());
        return new IntentRecognitionRecord(
                id,
                snapshot.tenantId(),
                snapshot.userId(),
                snapshot.sessionId(),
                snapshot.runId(),
                snapshot.commandId(),
                query,
                sha256(snapshot.queryText()),
                status,
                intentId,
                intent.intentName(),
                resourceId,
                intent.confidence(),
                source,
                candidateCount(items),
                snapshot.confidenceThreshold(),
                accepted,
                route == null || route.type() == null ? null : route.type().name(),
                route == null ? null : route.selectedAgentCode(),
                route == null ? null : route.reason(),
                stringValue(raw.get("resultMessage")),
                truncate(toJson(items), properties.normalizedMaxRawJsonLength()),
                truncate(toJson(rawResponse), properties.normalizedMaxRawJsonLength()),
                errorMessage(status, raw),
                snapshot.latencyMs(),
                snapshot.createdAt()
        );
    }

    private String status(IntentDecision intent) {
        if (intent == null) {
            return "NO_MATCH";
        }
        if ("finance.runtime.no_intent".equals(intent.intentCode())) {
            return "NO_MATCH";
        }
        if ("finance.runtime.intent_error".equals(intent.intentCode())) {
            return "FAILED";
        }
        if ("finance.runtime.degraded".equals(intent.intentCode())
                || "http-intent-degraded".equals(stringValue(intent.raw().get("source")))) {
            return "DEGRADED";
        }
        return "SUCCESS";
    }

    private boolean accepted(IntentDecision intent, RouteTarget route) {
        if (intent == null || route == null || intent.candidateDomainAgentId() == null) {
            return false;
        }
        if (route.type() == RouteType.AGENT_RUNTIME
                && (route.runtimeProfile() == RuntimeProfile.DOMAIN_EXPERT
                || "ROUTE_SINGLE".equalsIgnoreCase(stringValue(intent.slots().get("routeAction"))))) {
            return !intent.candidateDomainAgentId().isBlank();
        }
        return route.type() == RouteType.DOMAIN_AGENT
                && intent.candidateDomainAgentId().equals(route.selectedAgentCode());
    }

    private String errorMessage(String status, Map<String, Object> raw) {
        if ("SUCCESS".equals(status)) {
            return null;
        }
        return firstText(stringValue(raw.get("reason")), stringValue(raw.get("message")));
    }

    private int candidateCount(Object items) {
        return items instanceof List<?> list ? list.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private Object nested(Object root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("意图识别记录 JSON 序列化失败", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 14)) + "...[TRUNCATED]";
    }

    private String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }
}
