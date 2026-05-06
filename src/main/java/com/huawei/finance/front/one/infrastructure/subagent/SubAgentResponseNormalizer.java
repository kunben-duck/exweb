package com.huawei.finance.front.one.infrastructure.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.task.BusinessObjectRef;
import com.huawei.finance.front.one.domain.task.RequiredInput;
import com.huawei.finance.front.one.domain.task.SubAgentTaskResult;
import com.huawei.finance.front.one.domain.task.TaskStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * SubAgent 响应标准化器。
 *
 * <p>第三方 SubAgent 短期内可能返回标准 JSON、markdown JSON code block 或普通自然语言文本。
 * 本类统一把这些响应归一成 SubAgentTaskResult；无法可靠判断时不继续强粘，而是转成
 * WAITING_USER_CONFIRMATION，并保留 rawNormalizedStatus=UNKNOWN 供诊断。</p>
 */
@Component
public class SubAgentResponseNormalizer {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern JSON_CODE_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_CONFIRMATION =
            "我还不能确认报销任务是否进入下一步。你是要继续处理刚才的报销单，还是开始新的任务？";

    private final ObjectMapper objectMapper;

    public SubAgentResponseNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 标准化 SubAgent 原始响应。
     *
     * @param rawResponse 下游 HTTP 返回文本。
     * @return 标准任务结果。
     */
    public SubAgentTaskResult normalize(String rawResponse) {
        String rawText = rawResponse == null ? "" : rawResponse.trim();
        Map<String, Object> parsed = parseJson(rawText);
        if (!parsed.isEmpty()) {
            return fromJson(parsed, rawText);
        }
        return fromText(rawText);
    }

    private SubAgentTaskResult fromJson(Map<String, Object> parsed, String rawText) {
        String message = firstText(parsed, "message", "reply", "content", "text");
        TaskStatus rawStatus = normalizeStatus(firstText(parsed, "taskStatus", "status", "task_status"));
        if (rawStatus == TaskStatus.UNKNOWN) {
            return unknown(message, rawText, parsed);
        }
        TaskStatus publicStatus = rawStatus == TaskStatus.UNKNOWN ? TaskStatus.WAITING_USER_CONFIRMATION : rawStatus;
        String confirmationQuestion = firstText(parsed, "confirmationQuestion", "question", "clarification");
        return new SubAgentTaskResult(
                firstNonBlank(message, confirmationQuestion, ""),
                publicStatus,
                rawStatus,
                requiredInputs(firstValue(parsed, "requiredInputs", "missingInputs", "missingMaterials", "required_inputs")),
                firstText(parsed, "agentSessionId", "sessionId", "agent_session_id"),
                businessObjectRefs(firstValue(parsed, "businessObjectRefs", "objects", "business_objects")),
                doubleValue(firstValue(parsed, "confidence", "score"), 1.0),
                confirmationQuestion,
                parsed
        );
    }

    private SubAgentTaskResult fromText(String rawText) {
        String normalized = rawText.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "请提供", "请上传", "还需要", "补充", "缺少", "发票号", "发票图片")) {
            return new SubAgentTaskResult(rawText, TaskStatus.REQUIRES_USER_INPUT, TaskStatus.REQUIRES_USER_INPUT,
                    inferRequiredInputs(normalized), null, List.of(), 0.72, null, raw(rawText, "text-inferred-user-input"));
        }
        if (containsAny(normalized, "已完成", "提交成功", "创建成功", "报销单已", "处理完成")) {
            return new SubAgentTaskResult(rawText, TaskStatus.COMPLETED, TaskStatus.COMPLETED,
                    List.of(), null, List.of(), 0.72, null, raw(rawText, "text-inferred-completed"));
        }
        if (containsAny(normalized, "处理中", "请稍后", "等待", "已提交", "正在处理")) {
            return new SubAgentTaskResult(rawText, TaskStatus.WAITING_EXTERNAL_SYSTEM, TaskStatus.WAITING_EXTERNAL_SYSTEM,
                    List.of(), null, List.of(), 0.68, null, raw(rawText, "text-inferred-waiting-external-system"));
        }
        return unknown(rawText, rawText, raw(rawText, "text-unknown"));
    }

    private SubAgentTaskResult unknown(String message, String rawText, Map<String, Object> raw) {
        return new SubAgentTaskResult(
                DEFAULT_CONFIRMATION,
                TaskStatus.WAITING_USER_CONFIRMATION,
                TaskStatus.UNKNOWN,
                List.of(),
                null,
                List.of(),
                0.0,
                DEFAULT_CONFIRMATION,
                raw.isEmpty() ? raw(rawText, "unknown") : raw
        );
    }

    private Map<String, Object> parseJson(String rawText) {
        String candidate = rawText;
        Matcher matcher = JSON_CODE_BLOCK.matcher(rawText);
        if (matcher.find()) {
            candidate = matcher.group(1);
        }
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(candidate, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private TaskStatus normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return TaskStatus.UNKNOWN;
        }
        TaskStatus direct = TaskStatus.from(value, null);
        if (direct != null) {
            return direct;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "need_user", "requires_user", "missing", "need_more", "待补充")) {
            return TaskStatus.REQUIRES_USER_INPUT;
        }
        if (containsAny(normalized, "waiting", "processing", "pending", "submitted", "处理中")) {
            return TaskStatus.WAITING_EXTERNAL_SYSTEM;
        }
        if (containsAny(normalized, "done", "success", "completed", "finished", "成功")) {
            return TaskStatus.COMPLETED;
        }
        if (containsAny(normalized, "failed", "error", "失败")) {
            return TaskStatus.FAILED;
        }
        if (containsAny(normalized, "cancelled", "canceled", "取消")) {
            return TaskStatus.CANCELLED;
        }
        return TaskStatus.UNKNOWN;
    }

    private List<RequiredInput> requiredInputs(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<RequiredInput> inputs = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                inputs.add(new RequiredInput(firstText(map, "name", "field", "code"),
                        firstText(map, "description", "label", "message"),
                        firstText(map, "type", "dataType"),
                        booleanValue(map.get("required"), true)));
            } else if (item != null) {
                inputs.add(new RequiredInput(String.valueOf(item), String.valueOf(item), "string", true));
            }
        }
        return List.copyOf(inputs);
    }

    private List<RequiredInput> inferRequiredInputs(String normalizedText) {
        if (containsAny(normalizedText, "图片", "上传", "附件", "照片")) {
            return List.of(new RequiredInput("invoiceImage", "请上传发票图片或附件", "image", true));
        }
        if (containsAny(normalizedText, "金额")) {
            return List.of(new RequiredInput("amount", "请提供报销金额", "number", true));
        }
        if (containsAny(normalizedText, "发票号", "票号")) {
            return List.of(new RequiredInput("invoiceNo", "请提供发票号", "string", true));
        }
        return List.of(new RequiredInput("missingInfo", "请补充完成报销所需信息", "string", true));
    }

    private List<BusinessObjectRef> businessObjectRefs(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<BusinessObjectRef> refs = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                refs.add(new BusinessObjectRef(firstText(map, "objectType", "type"),
                        firstText(map, "objectId", "id"),
                        firstText(map, "displayName", "name"),
                        safeMap(map.get("attributes"))));
            }
        }
        return List.copyOf(refs);
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
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

    private Map<String, Object> safeMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        source.forEach((key, val) -> map.put(String.valueOf(key), val));
        return Map.copyOf(map);
    }

    private Map<String, Object> raw(String rawText, String mode) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("rawText", rawText);
        raw.put("normalizationMode", mode);
        return Map.copyOf(raw);
    }
}
