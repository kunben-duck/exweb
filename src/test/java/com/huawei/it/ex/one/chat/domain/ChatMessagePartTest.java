package com.huawei.it.ex.one.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatMessagePartTest {
    private static final Map<String, String> EXPECTED_TITLES = Map.ofEntries(
            Map.entry("ANSWER", "最终回答"),
            Map.entry("MESSAGE_SNAPSHOT", "回答快照"),
            Map.entry("PROGRESS", "运行进度"),
            Map.entry("METADATA", "运行元数据"),
            Map.entry("AGENT", "Agent 调用"),
            Map.entry("THINKING", "思考过程"),
            Map.entry("TOOL", "工具调用"),
            Map.entry("REFERENCE", "引用来源"),
            Map.entry("CARD", "卡片展示"),
            Map.entry("CLARIFICATION_REQUEST", "澄清请求"),
            Map.entry("CLARIFICATION_RESPONSE", "澄清回答"),
            Map.entry("AGENT_CLARIFICATION_REQUEST", "Agent 澄清请求"),
            Map.entry("AGENT_CLARIFICATION_RESPONSE", "Agent 澄清回答"),
            Map.entry("INTENT_CLARIFICATION_REQUEST", "意图澄清请求"),
            Map.entry("INTENT_CLARIFICATION_RESPONSE", "意图澄清回答"),
            Map.entry("DOMAIN_AGENT_REFUSAL", "领域 Agent 拒答"),
            Map.entry("ROUTE_SWITCH_CONFIRMATION_REQUEST", "路由切换确认"),
            Map.entry("ROUTE_SWITCH_CONFIRMATION_RESPONSE", "路由切换确认结果"),
            Map.entry("ROUTE_SWITCH_DECLINED", "路由切换已拒绝")
    );

    @Test
    void knownPartTypesKeepTheirDefaultTitles() {
        EXPECTED_TITLES.forEach((partType, expectedTitle) ->
                assertThat(part(partType).title()).isEqualTo(expectedTitle));
    }

    @Test
    void blankAndUnknownPartTypesUseRuntimeEventTitle() {
        ChatMessagePart blank = part(" ");

        assertThat(blank.partType()).isEqualTo("RUNTIME_EVENT");
        assertThat(blank.title()).isEqualTo("运行事件");
        assertThat(part("FUTURE_EVENT").title()).isEqualTo("运行事件");
    }

    private ChatMessagePart part(String partType) {
        return new ChatMessagePart(
                "part1", "tenant1", "user1", "session1", "message1", "run1",
                partType, "source", "content", Map.of(), 1, Instant.EPOCH);
    }
}
