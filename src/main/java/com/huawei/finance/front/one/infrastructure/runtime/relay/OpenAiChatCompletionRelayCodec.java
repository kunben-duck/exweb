package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.memory.LongTermMemoryItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * DeepSeek/OpenAI-compatible Chat Completions 协议编解码器。
 *
 * <p>该类服务于 {@link DeepSeekChatCompletionsRuntimeAdapter}。当真实 Relay streamable-http 服务尚未就绪时，
 * adapter 可以把 {@link AgentRuntimeRequest} 转换为 Chat Completions 请求体，并把非流式 JSON 或流式 SSE
 * 响应转换回标准 {@link ChatEvent}。业务层仍只依赖 AgentRuntime 防腐层，不感知外部模型 API 细节。</p>
 */
@Component
public class OpenAiChatCompletionRelayCodec {
    private final ObjectMapper objectMapper;

    public OpenAiChatCompletionRelayCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构造 Chat Completions 兼容请求体。
     *
     * @param request SuperAgent 标准 Runtime 请求。
     * @param properties Relay adapter 配置。
     * @return 可直接作为 JSON body 发送给 DeepSeek/OpenAI-compatible 接口的 Map。
     */
    public Map<String, Object> buildRequestBody(AgentRuntimeRequest request, RelayAgentProperties properties) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", buildMessages(request, properties));
        body.put("stream", properties.isStream());
        if (properties.isThinkingEnabled()) {
            body.put("thinking", Map.of("type", "enabled"));
        }
        if (hasText(properties.getReasoningEffort())) {
            body.put("reasoning_effort", properties.getReasoningEffort().trim());
        }
        return body;
    }

    /**
     * 解析非流式 Chat Completions 响应。
     *
     * @param runId 当前 run 标识。
     * @param sessionId 当前会话标识。
     * @param responseBody 外部模型返回的完整 JSON 字符串。
     * @return 标准聊天事件列表；如果没有 assistant content，则返回空列表。
     */
    public List<ChatEvent> decodeBlockingResponse(String runId, String sessionId, String responseBody) {
        String content = extractAssistantContent(parseJson(responseBody));
        return content.isBlank() ? List.of() : List.of(MessageDeltaEvent.of(runId, sessionId, content));
    }

    /**
     * 解析流式 Chat Completions SSE 响应。
     *
     * <p>兼容两类 WebClient 解码形态：一类是原始 {@code data: ...\n\n} 文本块，另一类是 HTTP
     * message reader 已经剥离 SSE framing 后的 JSON data 字符串。</p>
     *
     * @param runId 当前 run 标识。
     * @param sessionId 当前会话标识。
     * @param chunks WebClient 返回的字符串流。
     * @return 标准 assistant delta 事件流。
     */
    public Flux<ChatEvent> decodeStreamingResponse(String runId, String sessionId, Flux<String> chunks) {
        return Flux.defer(() -> {
            StreamingDecoder decoder = new StreamingDecoder(runId, sessionId);
            return chunks.concatWithValues("\n\n")
                    .concatMap(chunk -> Flux.fromIterable(decoder.accept(chunk)));
        });
    }

    private List<Map<String, String>> buildMessages(AgentRuntimeRequest request, RelayAgentProperties properties) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (hasText(properties.getSystemPrompt())) {
            messages.add(message("system", properties.getSystemPrompt().trim()));
        }
        String context = buildContext(request);
        if (hasText(context)) {
            messages.add(message("system", context));
        }
        messages.add(message("user", buildUserMessage(request)));
        return messages;
    }

    private String buildContext(AgentRuntimeRequest request) {
        if (request.memoryContext() == null || request.memoryContext().isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder("以下是 SuperAgent 可选上下文，只能作为参考，不要编造事实：");
        if (!request.memoryContext().recentMessages().isEmpty()) {
            context.append("\n最近对话：");
            for (ChatMessage message : request.memoryContext().recentMessages()) {
                context.append("\n- ").append(message.role()).append(": ").append(message.content());
            }
        }
        if (!request.memoryContext().longTermMemories().isEmpty()) {
            context.append("\n长期记忆：");
            for (LongTermMemoryItem item : request.memoryContext().longTermMemories()) {
                context.append("\n- ").append(item.memoryType()).append(": ").append(item.content());
            }
        }
        return context.toString();
    }

    private String buildUserMessage(AgentRuntimeRequest request) {
        StringBuilder message = new StringBuilder(request.message() == null ? "" : request.message());
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            message.append("\n\n用户已关联以下文档附件，具体文件内容需由下游 Runtime 根据 documentId 自行拉取或解析：");
            for (AttachmentRef attachment : request.attachments()) {
                message.append("\n- documentId=").append(attachment.documentId());
                appendIfPresent(message, ", name=", attachment.name());
                appendIfPresent(message, ", contentType=", attachment.contentType());
                if (attachment.sizeBytes() != null) {
                    message.append(", sizeBytes=").append(attachment.sizeBytes());
                }
            }
        }
        return message.toString();
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private void appendIfPresent(StringBuilder target, String label, String value) {
        if (hasText(value)) {
            target.append(label).append(value.trim());
        }
    }

    private String extractAssistantContent(JsonNode root) {
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = firstText(error, "message", "type", "code");
            throw new RelayRuntimeProtocolException(hasText(message) ? message : "Chat Completions API returned error");
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (JsonNode choice : choices) {
            String delta = firstText(choice.path("delta"), "content");
            if (!hasText(delta)) {
                delta = firstText(choice.path("message"), "content");
            }
            if (hasText(delta)) {
                content.append(delta);
            }
        }
        return content.toString();
    }

    private JsonNode parseJson(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new RelayRuntimeProtocolException("Invalid Chat Completions JSON response");
        }
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isTextual() && hasText(value.asText())) {
                return value.asText();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private final class StreamingDecoder {
        private final String runId;
        private final String sessionId;
        private final StringBuilder buffer = new StringBuilder();

        private StreamingDecoder(String runId, String sessionId) {
            this.runId = runId;
            this.sessionId = sessionId;
        }

        private List<ChatEvent> accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return List.of();
            }
            if (buffer.isEmpty()) {
                StandaloneDecodeResult standalone = tryDecodeStandalone(chunk);
                if (standalone.consumed()) {
                    return standalone.events();
                }
            }
            buffer.append(chunk);
            return drainBufferedBlocks();
        }

        private StandaloneDecodeResult tryDecodeStandalone(String chunk) {
            String trimmed = chunk.trim();
            if (trimmed.isEmpty()) {
                return StandaloneDecodeResult.consumed(List.of());
            }
            if (trimmed.startsWith("data:")) {
                return StandaloneDecodeResult.consumed(decodeSseBlock(trimmed));
            }
            if ("[DONE]".equals(trimmed)) {
                return StandaloneDecodeResult.consumed(List.of());
            }
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    return StandaloneDecodeResult.consumed(decodeJsonPayload(trimmed));
                } catch (RelayRuntimeProtocolException ex) {
                    return StandaloneDecodeResult.notConsumed();
                }
            }
            return StandaloneDecodeResult.notConsumed();
        }

        private List<ChatEvent> drainBufferedBlocks() {
            List<ChatEvent> events = new ArrayList<>();
            int delimiter;
            while ((delimiter = nextDelimiter(buffer)) >= 0) {
                String block = buffer.substring(0, delimiter);
                int removeLength = delimiter;
                while (removeLength < buffer.length()
                        && (buffer.charAt(removeLength) == '\n' || buffer.charAt(removeLength) == '\r')) {
                    removeLength++;
                }
                buffer.delete(0, removeLength);
                events.addAll(decodeSseBlock(block));
            }
            return events;
        }

        private int nextDelimiter(StringBuilder value) {
            int lf = value.indexOf("\n\n");
            int crlf = value.indexOf("\r\n\r\n");
            if (lf < 0) {
                return crlf;
            }
            if (crlf < 0) {
                return lf;
            }
            return Math.min(lf, crlf);
        }

        private List<ChatEvent> decodeSseBlock(String block) {
            String trimmed = block == null ? "" : block.trim();
            if (trimmed.isEmpty()) {
                return List.of();
            }
            List<String> dataLines = new ArrayList<>();
            for (String line : trimmed.split("\\R")) {
                String normalized = line.trim();
                if (normalized.startsWith("data:")) {
                    dataLines.add(normalized.substring("data:".length()).trim());
                }
            }
            String payload = dataLines.isEmpty() ? trimmed : String.join("\n", dataLines);
            if (payload.isBlank() || "[DONE]".equals(payload)) {
                return List.of();
            }
            return decodeJsonPayload(payload);
        }

        private List<ChatEvent> decodeJsonPayload(String payload) {
            String content = extractAssistantContent(parseJson(payload));
            return content.isBlank() ? List.of() : List.of(MessageDeltaEvent.of(runId, sessionId, content));
        }
    }

    /**
     * 单个 WebClient chunk 的独立解析结果。
     *
     * <p>DeepSeek 在开启 thinking 时会先返回大量合法但 content 为空的 {@code reasoning_content}
     * chunk。此时必须把 chunk 视为“已消费但没有可对前端展示的 delta”，否则会污染后续 buffer，
     * 导致真正的 assistant content 无法被解析。</p>
     *
     * @param consumed 当前 chunk 是否已经被完整识别和消费。
     * @param events 当前 chunk 转换出的前端聊天事件；可能为空。
     */
    private record StandaloneDecodeResult(boolean consumed, List<ChatEvent> events) {
        private static StandaloneDecodeResult consumed(List<ChatEvent> events) {
            return new StandaloneDecodeResult(true, events == null ? List.of() : events);
        }

        private static StandaloneDecodeResult notConsumed() {
            return new StandaloneDecodeResult(false, List.of());
        }
    }
}
