package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatMessagePartDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单次 run 内的 assistant 汇总状态。
 *
 * <p>流式 delta 负责实时草稿；下游最终 {@code message.snapshot} 是更权威的最终正文。
 * {@code runtime.*} 事件只保存为历史过程 parts，不混入 assistant 正文。若没有正文但存在卡片、
 * 引用、思考或进度等用户可见 part，也会创建一条空正文 assistant 消息作为 parts 挂载点。</p>
 */
final class AssistantAssembly {
    private final StringBuilder deltaDraft = new StringBuilder();
    private final List<ChatMessagePartDraft> parts = new ArrayList<>();
    private String snapshot;

    void observe(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return;
        }
        if ("message.delta".equals(event.type())) {
            Object delta = event.payload().get("delta");
            if (delta != null) {
                deltaDraft.append(delta);
            }
            return;
        }
        if ("message.snapshot".equals(event.type())) {
            Object content = event.payload().get("content");
            if (content != null) {
                snapshot = String.valueOf(content);
            }
            return;
        }
        if (event.type() != null && event.type().startsWith("runtime.")) {
            parts.add(runtimePart(event));
        }
    }

    boolean shouldPersistMessage() {
        return hasContent() || parts.stream().anyMatch(AssistantAssembly::userVisiblePart);
    }

    String finalContent() {
        return snapshot != null ? snapshot : deltaDraft.toString();
    }

    List<ChatMessagePartDraft> parts() {
        return List.copyOf(parts);
    }

    private boolean hasContent() {
        return snapshot != null && !snapshot.isEmpty() || !deltaDraft.isEmpty();
    }

    private static boolean userVisiblePart(ChatMessagePartDraft part) {
        if (part == null || part.partType() == null) {
            return false;
        }
        return switch (part.partType()) {
            case "PROGRESS", "AGENT", "THINKING", "TOOL", "REFERENCE", "CARD" -> true;
            default -> false;
        };
    }

    private static ChatMessagePartDraft runtimePart(ChatEvent event) {
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        String sourceType = stringValue(payload.get("sourceType"));
        return new ChatMessagePartDraft(partType(event.type()), sourceType, contentText(event.type(), payload), payload);
    }

    private static String partType(String eventType) {
        return switch (eventType) {
            case "runtime.progress" -> "PROGRESS";
            case "runtime.metadata" -> "METADATA";
            case "runtime.agent" -> "AGENT";
            case "runtime.thinking" -> "THINKING";
            case "runtime.tool" -> "TOOL";
            case "runtime.reference" -> "REFERENCE";
            case "runtime.card" -> "CARD";
            default -> "RUNTIME_EVENT";
        };
    }

    private static String contentText(String eventType, Map<String, Object> payload) {
        if ("runtime.progress".equals(eventType)) {
            return firstText(payload, "text", "message");
        }
        if ("runtime.agent".equals(eventType)) {
            return firstText(payload, "task", "agentName");
        }
        if ("runtime.tool".equals(eventType)) {
            String toolName = firstText(payload, "toolName");
            String preview = firstText(payload, "inputPreview");
            if (toolName != null && preview != null) {
                return toolName + ": " + preview;
            }
            return toolName == null ? preview : toolName;
        }
        if ("runtime.thinking".equals(eventType)) {
            String text = firstText(payload, "text", "title");
            if (text != null) {
                return text;
            }
            String status = firstText(payload, "status");
            String operationId = firstText(payload, "operationId");
            return operationId == null ? status : status + ": " + operationId;
        }
        if ("runtime.metadata".equals(eventType)) {
            return firstText(payload, "projectHome", "metadataType");
        }
        if ("runtime.reference".equals(eventType)) {
            return firstText(payload, "delta", "title", "url", "referenceType", "sourceType");
        }
        if ("runtime.card".equals(eventType)) {
            return firstText(payload, "delta", "cardUrl", "intent", "skillId", "cardType", "sourceType");
        }
        return firstText(payload, "text", "displayText", "sourceType");
    }

    private static String firstText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            String value = stringValue(payload.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
