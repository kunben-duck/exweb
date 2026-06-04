package com.huawei.finance.front.one.infrastructure.runtime.relay;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Relay 响应到 ChatService 标准事件的映射配置。
 *
 * <p>该配置只作用于 Relay adapter 防腐层，用于兼容 Relay 版本演进带来的响应字段变化。
 * ChatService 对前端输出的事件协议仍由本服务定义。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.agent-runtime.relay")
public class RelayRuntimeMappingProperties {
    /** 可被识别为 assistant 正文增量的 Relay 事件类型。 */
    private List<String> answerEventTypes = List.of("agent", "message.delta", "answer", "output");
    /** 可从正文事件中抽取文本的字段名，按顺序优先匹配。 */
    private List<String> answerContentFields = List.of("content", "context", "delta", "message", "text", "output_text");
    /** type=agent 且没有 content 时，是否允许把 context 作为 assistant 正文。 */
    private boolean agentContextAsAnswer = true;

    public List<String> getAnswerEventTypes() {
        return answerEventTypes;
    }

    public void setAnswerEventTypes(List<String> answerEventTypes) {
        this.answerEventTypes = answerEventTypes;
    }

    public List<String> getAnswerContentFields() {
        return answerContentFields;
    }

    public void setAnswerContentFields(List<String> answerContentFields) {
        this.answerContentFields = answerContentFields;
    }

    public boolean isAgentContextAsAnswer() {
        return agentContextAsAnswer;
    }

    public void setAgentContextAsAnswer(boolean agentContextAsAnswer) {
        this.agentContextAsAnswer = agentContextAsAnswer;
    }

    public Set<String> normalizedAnswerEventTypes() {
        return normalizeSet(answerEventTypes, List.of("agent", "message.delta", "answer", "output"));
    }

    public List<String> normalizedAnswerContentFields() {
        if (answerContentFields == null || answerContentFields.isEmpty()) {
            return List.of("content", "context", "delta", "message", "text", "output_text");
        }
        return answerContentFields.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private Set<String> normalizeSet(List<String> values, List<String> defaults) {
        List<String> source = values == null || values.isEmpty() ? defaults : values;
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            if (value != null && !value.isBlank()) {
                normalized.add(RelayRuntimeResponseNormalizer.normalizeTypeName(value));
            }
        }
        return normalized;
    }
}
