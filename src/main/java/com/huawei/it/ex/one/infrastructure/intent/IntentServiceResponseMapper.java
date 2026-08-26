package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.routing.DomainExpertAccessNameResolver;
import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 将意图服务 HTTP 响应转换成 ChatService 稳定领域模型。
 *
 * <p>当前下游返回结构为 {@code code -> data -> result}，其中 {@code routeAction}
 * 是唯一裁决字段。ROUTE_SINGLE 时 item.intentId 表示业务意图编码，item.accessName 归一化后
 * 表示可绑定的 DomainAgentId/skillId。
 * 后续如果下游字段或包装层变化，只修改这里的解析逻辑；应用层仍只依赖稳定领域模型。</p>
 */
@Component
public class IntentServiceResponseMapper {
    private static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final IntentServiceHttpProperties properties;
    private final DomainExpertAccessNameResolver domainExpertResolver;
    private final SensitiveInformationAccessNameResolver sensitiveInformationResolver;

    public IntentServiceResponseMapper(ObjectMapper objectMapper, IntentServiceHttpProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.domainExpertResolver = new DomainExpertAccessNameResolver(
                properties == null ? null : properties.getDomainExpertAccessNamePrefix());
        this.sensitiveInformationResolver = new SensitiveInformationAccessNameResolver(
                properties == null ? null : properties.getSensitiveInformationAccessName());
    }

    /**
     * 解析下游响应并返回兼容的最终意图决策。
     *
     * <p>旧调用方仍可通过本方法拿到最终决策；新路由链路应优先使用
     * {@link #toRecognitionResult(JsonNode)} 以保留 CLARIFY 等非最终态。</p>
     *
     * @param root 下游 HTTP 响应 JSON。
     * @return 应用层意图决策。
     */
    public IntentDecision toDecision(JsonNode root) {
        IntentRecognitionResult result = toRecognitionResult(root);
        return result.decision() == null ? degraded("intent response has no final decision") : result.decision();
    }

    /**
     * 解析新意图决策接口响应。
     *
     * <p>{@code routeAction} 是 ChatService 的唯一路由裁决入口：ROUTE_SINGLE 直接取唯一
     * item 的 {@code accessName} 归一化为 DomainAgentId；ROUTE_MULTI/NO_MATCH 均进入 Relay Runtime；
     * CLARIFY 进入意图澄清等待态。confidence 仅用于记录和排障，不参与是否绑定 DomainAgent 的判断。</p>
     */
    public IntentRecognitionResult toRecognitionResult(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return IntentRecognitionResult.degraded(degraded("empty intent response"));
        }
        if (root.hasNonNull("code") && root.path("code").asInt(200) != 200) {
            return IntentRecognitionResult.degraded(intentError(root, "intent response code is not 200"));
        }
        String rootStatus = text(root.path("status"));
        if (rootStatus != null && !"success".equalsIgnoreCase(rootStatus)) {
            return IntentRecognitionResult.degraded(intentError(root, "intent response status is not success"));
        }
        JsonNode data = root.path("data");
        String status = text(data.path("status"));
        if (status != null && !"success".equalsIgnoreCase(status)) {
            return IntentRecognitionResult.degraded(intentError(root, "intent response status is not success"));
        }
        JsonNode result = data.path("result");
        String routeAction = text(result.path("routeAction"));
        if ("CLARIFY".equalsIgnoreCase(routeAction)) {
            if (hasMalformedDomainExpertCandidate(result.path("clarification").path("candidateIntents"))) {
                return IntentRecognitionResult.degraded(protocolError(root, result, null,
                        "CLARIFY domain expert accessName has no roleName"));
            }
            return IntentRecognitionResult.waitingClarification(clarificationPayload(root, result),
                    firstText(result.path("intentSessionId"), data.path("intentSessionId")),
                    firstText(result.path("intentRequestId"), data.path("intentRequestId")));
        }
        if ("ROUTE_MULTI".equalsIgnoreCase(routeAction)) {
            return IntentRecognitionResult.finalDecision(complexDecision(root, result,
                    "finance.runtime.route_multi", "多意图命中，进入 Relay Runtime",
                    "routeAction=ROUTE_MULTI"));
        }
        if ("NO_MATCH".equalsIgnoreCase(routeAction)) {
            return IntentRecognitionResult.finalDecision(complexDecision(root, result,
                    "finance.runtime.no_intent",
                    "未识别到可用意图，进入 " + properties.normalizedNoMatchAgentName(),
                    "routeAction=NO_MATCH"));
        }
        if ("ROUTE_SINGLE".equalsIgnoreCase(routeAction)) {
            return singleRouteResult(root, result);
        }

        // 新意图服务以 routeAction 作为唯一裁决字段。缺失时不再按 items/confidence 猜测 DomainAgent。
        return IntentRecognitionResult.degraded(protocolError(root, result, null,
                routeAction == null ? "routeAction missing" : "unknown routeAction: " + routeAction));
    }

    private IntentRecognitionResult singleRouteResult(JsonNode root, JsonNode result) {
        JsonNode items = result.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return IntentRecognitionResult.degraded(protocolError(root, result, null,
                    "ROUTE_SINGLE response has no item"));
        }
        JsonNode selected = items.get(0);
        String domainAgentId = normalizeDomainAgentId(text(selected.path("accessName")));
        if (domainAgentId == null) {
            return IntentRecognitionResult.degraded(protocolError(root, result, selected,
                    "ROUTE_SINGLE accessName missing after normalization"));
        }
        if (!sensitiveInformationResolver.matches(domainAgentId)
                && domainExpertResolver.resolve(domainAgentId).malformedDomainExpert()) {
            return IntentRecognitionResult.degraded(protocolError(root, result, selected,
                    "ROUTE_SINGLE domain expert accessName has no roleName"));
        }
        return IntentRecognitionResult.finalDecision(itemToDomainAgentDecision(
                root, selected, result, domainAgentId, "routeAction=ROUTE_SINGLE"));
    }

    private IntentDecision itemToDomainAgentDecision(JsonNode root, JsonNode selected, JsonNode result,
                                                     String domainAgentId, String reason) {
        String intentId = text(selected.path("intentId"));
        String resourceId = text(selected.path("resourceInstruction").path("resourceId"));
        double confidence = confidence(selected);
        Map<String, Object> slots = new LinkedHashMap<>();
        putIfPresent(slots, "routeAction", text(result.path("routeAction")));
        putIfPresent(slots, "intentId", intentId);
        putIfPresent(slots, "accessName", domainAgentId);
        putIfPresent(slots, "resourceId", resourceId);
        putIfPresent(slots, "source", text(selected.path("source")));
        if (!selected.path("score").isMissingNode() && !selected.path("score").isNull()) {
            slots.put("score", nodeToObject(selected.path("score")));
        }

        return new IntentDecision(
                blankToDefault(intentId, "finance.intent.unknown"),
                blankToDefault(text(selected.path("intentName")), "未知意图"),
                TaskComplexity.SIMPLE,
                confidence,
                true,
                domainAgentId,
                slots,
                List.of(),
                rawWithSelected(root, selected, result, reason)
        );
    }

    private String normalizeDomainAgentId(String accessName) {
        return IntentAccessNameNormalizer.normalize(
                accessName,
                properties == null ? null : properties.getResponseAccessNamePrefix());
    }

    private IntentDecision complexDecision(JsonNode root, JsonNode result, String code, String name, String reason) {
        String routeAction = text(result.path("routeAction"));
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("routeAction", routeAction == null ? "" : routeAction);
        if ("ROUTE_MULTI".equalsIgnoreCase(routeAction)) {
            List<String> candidateIntentNames = candidateIntentNames(result.path("items"));
            if (!candidateIntentNames.isEmpty()) {
                slots.put("candidateIntentNames", candidateIntentNames);
            }
        }
        return new IntentDecision(
                code,
                name,
                TaskComplexity.COMPLEX,
                0.0,
                false,
                null,
                slots,
                List.of(),
                rawWithReason(root, reason)
        );
    }

    private List<String> candidateIntentNames(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonNode item : items) {
            String name = text(item.path("intentName"));
            if (name != null) {
                names.add(name.trim());
            }
        }
        return List.copyOf(names);
    }

    private Map<String, Object> clarificationPayload(JsonNode root, JsonNode result) {
        JsonNode clarification = result.path("clarification");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeAction", "CLARIFY");
        String clarificationType = text(clarification.path("type"));
        List<Map<String, Object>> candidates = normalizedCandidates(
                clarification.path("candidateIntents"));
        payload.put("clarification", normalizedClarification(clarification, candidates));
        putIfPresent(payload, "type", clarificationType);
        putIfPresent(payload, "clarificationType", clarificationType);
        putIfPresent(payload, "clarifyQuestion", text(clarification.path("clarifyQuestion")));
        if (!candidates.isEmpty()) {
            payload.put("candidateIntents", candidates);
        }
        return Map.copyOf(payload);
    }

    private Map<String, Object> normalizedClarification(
            JsonNode clarification,
            List<Map<String, Object>> candidates) {
        Object raw = nodeToObject(clarification);
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), value);
                }
            });
        }
        if (!candidates.isEmpty()) {
            normalized.put("candidateIntents", candidates);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private List<Map<String, Object>> normalizedCandidates(JsonNode candidates) {
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<Map<String, Object>> normalized = new java.util.ArrayList<>();
        for (JsonNode candidate : candidates) {
            Object raw = nodeToObject(candidate);
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    item.put(String.valueOf(key), value);
                }
            });
            String skillId = normalizeDomainAgentId(text(candidate.path("accessName")));
            if (skillId != null && (sensitiveInformationResolver.matches(skillId)
                    || !domainExpertResolver.resolve(skillId).malformedDomainExpert())) {
                item.put("skillId", skillId);
            }
            normalized.add(Collections.unmodifiableMap(item));
        }
        return List.copyOf(normalized);
    }

    private boolean hasMalformedDomainExpertCandidate(JsonNode candidates) {
        if (candidates == null || !candidates.isArray()) {
            return false;
        }
        for (JsonNode candidate : candidates) {
            String skillId = normalizeDomainAgentId(text(candidate.path("accessName")));
            if (skillId != null && !sensitiveInformationResolver.matches(skillId)
                    && domainExpertResolver.resolve(skillId).malformedDomainExpert()) {
                return true;
            }
        }
        return false;
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

    private IntentDecision protocolError(JsonNode root, JsonNode result, JsonNode selected, String reason) {
        Map<String, Object> slots = new LinkedHashMap<>();
        putIfPresent(slots, "routeAction", text(result == null ? null : result.path("routeAction")));
        if (selected != null && !selected.isNull() && !selected.isMissingNode()) {
            putIfPresent(slots, "intentId", text(selected.path("intentId")));
            putIfPresent(slots, "accessName", text(selected.path("accessName")));
            putIfPresent(slots, "resourceId", text(selected.path("resourceInstruction").path("resourceId")));
            putIfPresent(slots, "source", text(selected.path("source")));
        }
        Map<String, Object> raw = selected == null
                ? rawWithReason(root, reason)
                : rawWithSelected(root, selected, result, reason);
        return new IntentDecision("finance.runtime.intent_error", "意图服务协议异常",
                TaskComplexity.COMPLEX, 0.0, false, null, slots, List.of(), raw);
    }

    private Map<String, Object> rawWithSelected(JsonNode root, JsonNode selected, JsonNode result, String reason) {
        Map<String, Object> raw = rawWithReason(root, "intent response parsed");
        raw.put("reason", reason == null ? "intent response parsed" : reason);
        putIfPresent(raw, "routeAction", text(result.path("routeAction")));
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

    private String firstText(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            String value = text(node);
            if (value != null) {
                return value;
            }
        }
        return null;
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
