package com.huawei.finance.front.one.infrastructure.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将意图服务 HTTP 响应转换成 ChatService 稳定领域模型。
 *
 * <p>当前下游返回结构为 {@code code -> data -> result -> items[]}，其中 item 的
 * {@code resourceInstruction.resourceId} 表示可路由技能编码。后续如果下游字段或包装层变化，
 * 只修改这里的解析逻辑；应用层仍只依赖 {@link IntentDecision}。</p>
 */
@Component
public class IntentServiceResponseMapper {
    private static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public IntentServiceResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析下游响应并选择最高置信候选。
     *
     * <p>是否真正采用该候选由领域层 RoutingPolicy 根据配置化 confidence 阈值裁决。</p>
     *
     * @param root 下游 HTTP 响应 JSON。
     * @return 应用层意图决策。
     */
    public IntentDecision toDecision(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return degraded("empty intent response");
        }
        if (root.hasNonNull("code") && root.path("code").asInt(200) != 200) {
            return intentError(root, "intent response code is not 200");
        }
        JsonNode data = root.path("data");
        String status = text(data.path("status"));
        if (status != null && !"success".equalsIgnoreCase(status)) {
            return intentError(root, "intent response status is not success");
        }
        JsonNode result = data.path("result");
        JsonNode items = result.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return new IntentDecision(
                    "finance.runtime.no_intent",
                    "未识别到可用意图",
                    TaskComplexity.COMPLEX,
                    0.0,
                    false,
                    null,
                    Map.of(),
                    List.of(),
                    rawWithReason(root, "intent response has no items")
            );
        }

        JsonNode selected = selectHighestConfidence(items);
        String resourceId = text(selected.path("resourceInstruction").path("resourceId"));
        double confidence = confidence(selected);
        Map<String, Object> slots = new LinkedHashMap<>();
        putIfPresent(slots, "resourceId", resourceId);
        putIfPresent(slots, "source", text(selected.path("source")));
        if (!selected.path("score").isMissingNode() && !selected.path("score").isNull()) {
            slots.put("score", nodeToObject(selected.path("score")));
        }

        return new IntentDecision(
                blankToDefault(text(selected.path("intentId")), "finance.intent.unknown"),
                blankToDefault(text(selected.path("intentName")), "未知意图"),
                resourceId == null ? TaskComplexity.COMPLEX : TaskComplexity.SIMPLE,
                confidence,
                resourceId != null,
                resourceId,
                slots,
                List.of(),
                rawWithSelected(root, selected, result)
        );
    }

    /**
     * 构造下游不可用时的降级决策。
     */
    public IntentDecision degraded(String reason) {
        return new IntentDecision(
                "finance.runtime.degraded",
                "意图服务不可用，转入 AgentRuntime",
                TaskComplexity.COMPLEX,
                0.0,
                false,
                null,
                Map.of(),
                List.of(),
                Map.of("source", "http-intent-degraded", "reason", reason == null ? "" : reason)
        );
    }

    private IntentDecision intentError(JsonNode root, String reason) {
        return new IntentDecision("finance.runtime.intent_error", "意图服务返回失败",
                TaskComplexity.COMPLEX, 0.0, false, null, Map.of(), List.of(),
                rawWithReason(root, reason));
    }

    private JsonNode selectHighestConfidence(JsonNode items) {
        List<JsonNode> candidates = new ArrayList<>();
        items.forEach(candidates::add);
        return candidates.stream()
                .max(Comparator.comparingDouble(this::confidence))
                .orElse(items.get(0));
    }

    private Map<String, Object> rawWithSelected(JsonNode root, JsonNode selected, JsonNode result) {
        Map<String, Object> raw = rawWithReason(root, "intent response parsed");
        raw.put("selectedItem", nodeToObject(selected));
        putIfPresent(raw, "resultMessage", text(result.path("message")));
        return Map.copyOf(raw);
    }

    private Map<String, Object> rawWithReason(JsonNode root, String reason) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source", "http-intent-service");
        raw.put("reason", reason == null ? "" : reason);
        raw.put("response", nodeToObject(root));
        return raw;
    }

    private Object nodeToObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return objectMapper.convertValue(node, OBJECT_TYPE);
    }

    private double confidence(JsonNode item) {
        JsonNode value = item == null ? null : item.path("confidence");
        return value == null || value.isMissingNode() || value.isNull() ? 0.0 : value.asDouble(0.0);
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
