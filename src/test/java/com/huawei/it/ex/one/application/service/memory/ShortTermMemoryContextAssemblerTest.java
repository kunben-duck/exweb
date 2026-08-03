package com.huawei.it.ex.one.application.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.memory.RouteMemoryContext;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class ShortTermMemoryContextAssemblerTest {
    @Test
    void agentRuntimeAndIntentUseIndependentWindows() {
        MemoryProperties properties = enabledProperties();
        properties.getShortTerm().getAgentRuntime().setRecentTurns(2);
        properties.getShortTerm().getIntent().setRecentTurns(1);
        ShortTermMemoryContextAssembler assembler = assembler(properties);
        List<ChatMessage> source = List.of(
                message("m1", null, 1, "run-route", "user", "route query"),
                message("m2", "m1", 2, "run-route", "assistant", "route answer"),
                message("m3", "m2", 3, "run-follow-1", "user", "follow one"),
                message("m4", "m3", 4, "run-follow-1", "assistant", "answer one"),
                message("m5", "m4", 5, "run-follow-2", "user", "follow two"),
                message("m6", "m5", 6, "run-follow-2", "assistant", "answer two"));
        RouteMemoryContext routeMemory = routeMemory("run-route");
        MemoryContext memory = memory(source, assembler.agentRuntimeMessages(source), routeMemory);

        assertThat(memory.agentRuntimeMessages())
                .extracting(ConversationMemoryMessage::content)
                .containsExactly("follow one", "answer one", "follow two", "answer two");

        RouteMemoryContext intentMemory = assembler.projectIntent(
                memory, routeMemory, "user_correction", "run-current", Map.of()).routeMemory();

        assertThat(intentMessages(intentMemory))
                .containsExactly(new ConversationMemoryMessage("user", "follow two"));
    }

    @Test
    void tokenBudgetDropsOldestMessagesAndKeepsReadingOrder() {
        MemoryProperties properties = enabledProperties();
        properties.getShortTerm().getAgentRuntime().setRecentTurns(5);
        properties.getShortTerm().getAgentRuntime().setMaxContextTokens(5);
        ShortTermMemoryContextAssembler assembler = assembler(properties);

        List<ConversationMemoryMessage> selected = assembler.agentRuntimeMessages(List.of(
                message("m1", null, 1, "run-1", "user", "aaaa"),
                message("m2", "m1", 2, "run-1", "assistant", "bb"),
                message("m3", "m2", 3, "run-2", "user", "ccc")));

        assertThat(selected).containsExactly(
                new ConversationMemoryMessage("assistant", "bb"),
                new ConversationMemoryMessage("user", "ccc"));
    }

    @Test
    void oversizedLatestMessageIsTruncatedByUnicodeCodePoint() {
        MemoryProperties properties = enabledProperties();
        properties.getShortTerm().getAgentRuntime().setMaxContextTokens(2);
        ShortTermMemoryContextAssembler assembler = assembler(properties);

        List<ConversationMemoryMessage> selected = assembler.agentRuntimeMessages(List.of(
                message("m1", null, 1, "run-1", "user", "😀😀😀")));

        assertThat(selected).containsExactly(new ConversationMemoryMessage("user", "😀😀"));
    }

    @Test
    void intentTokenBudgetIsIndependentAndTruncatesLatestUserMessage() {
        MemoryProperties properties = enabledProperties();
        properties.getShortTerm().getAgentRuntime().setMaxContextTokens(1000);
        properties.getShortTerm().getIntent().setMaxContextTokens(2);
        ShortTermMemoryContextAssembler assembler = assembler(properties);
        List<ChatMessage> source = List.of(
                message("m1", null, 1, "run-route", "user", "route query"),
                message("m2", "m1", 2, "run-route", "assistant", "route answer"),
                message("m3", "m2", 3, "run-follow-1", "user", "中文历史"),
                message("m4", "m3", 4, "run-follow-2", "user", "😀😀😀"));
        RouteMemoryContext routeMemory = routeMemory("run-route");
        MemoryContext memory = memory(source, assembler.agentRuntimeMessages(source), routeMemory);

        RouteMemoryContext intentMemory = assembler.projectIntent(
                memory, routeMemory, "domain_reject", "run-current", Map.of()).routeMemory();

        assertThat(memory.agentRuntimeMessages())
                .extracting(ConversationMemoryMessage::content)
                .containsExactly("route query", "route answer", "中文历史", "😀😀😀");
        assertThat(intentMessages(intentMemory))
                .containsExactly(new ConversationMemoryMessage("user", "😀😀"));
    }

    @Test
    void clarifyAnswerUsesFrozenIntentMessages() {
        MemoryProperties properties = enabledProperties();
        ShortTermMemoryContextAssembler assembler = assembler(properties);
        RouteMemoryContext routeMemory = routeMemory("run-route");
        MemoryContext memory = memory(
                List.of(message("m3", null, 3, "run-later", "user", "later message")),
                List.of(),
                routeMemory);
        List<Map<String, Object>> frozen = List.of(Map.of("role", "user", "content", "frozen message"));

        ShortTermMemoryContextAssembler.IntentProjection projection = assembler.projectIntent(
                memory,
                routeMemory,
                "clarify_answer",
                "run-current",
                Map.of(ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY, frozen));

        assertThat(intentMessages(projection.routeMemory()))
                .containsExactly(new ConversationMemoryMessage("user", "frozen message"));
        assertThat(projection.frozenMessages()).contains(
                List.of(new ConversationMemoryMessage("user", "frozen message")));
    }

    @Test
    void ordinaryIntentAndClarificationDoNotReceiveShortTermMessages() {
        MemoryProperties properties = enabledProperties();
        ShortTermMemoryContextAssembler assembler = assembler(properties);
        RouteMemoryContext routeMemory = routeMemory("run-route");
        MemoryContext memory = memory(
                List.of(message("m2", null, 2, "run-follow", "user", "follow up")),
                List.of(),
                routeMemory);

        for (String trigger : List.of("first_turn", "clarify_answer", "fallback_followup")) {
            ShortTermMemoryContextAssembler.IntentProjection projection = assembler.projectIntent(
                    memory, routeMemory, trigger, "run-current", Map.of());

            assertThat(projection.routeMemory().history().getFirst())
                    .doesNotContainKey(ShortTermMemoryContextAssembler.INTENT_MESSAGES_KEY);
            assertThat(projection.frozenMessages()).isEmpty();
        }
    }

    @Test
    void missingVisibleRouteDoesNotCreateSyntheticRoute() {
        MemoryProperties properties = enabledProperties();
        ShortTermMemoryContextAssembler assembler = assembler(properties);
        MemoryContext memory = memory(
                List.of(message("m1", null, 1, "run-1", "user", "history")),
                List.of(),
                RouteMemoryContext.empty());

        ShortTermMemoryContextAssembler.IntentProjection projection = assembler.projectIntent(
                memory, RouteMemoryContext.empty(), "domain_reject", "run-current", Map.of());

        assertThat(projection.routeMemory().history()).isEmpty();
    }

    @Test
    void sameRunRouteDoesNotTreatEarlierMessagesAsDomainSessionMessages() {
        MemoryProperties properties = enabledProperties();
        ShortTermMemoryContextAssembler assembler = assembler(properties);
        RouteMemoryContext routeMemory = routeMemory("run-current");
        MemoryContext memory = memory(
                List.of(message("m1", null, 1, "run-previous", "user", "earlier message")),
                List.of(),
                routeMemory);

        RouteMemoryContext intentMemory = assembler.projectIntent(
                memory, routeMemory, "domain_reject", "run-current", Map.of()).routeMemory();

        assertThat(intentMessages(intentMemory)).isEmpty();
    }

    @Test
    void sourceWindowUsesLargestIndependentRequirementAndNormalizesValues() {
        MemoryProperties properties = enabledProperties();
        properties.getShortTerm().getAgentRuntime().setRecentTurns(2);
        properties.getShortTerm().getIntent().setRecentTurns(7);

        assertThat(properties.getShortTerm().sourceMessageLimit()).isEqualTo(14);

        properties.getShortTerm().setCacheRecentTurns(0);
        properties.getShortTerm().getAgentRuntime().setRecentTurns(-2);
        properties.getShortTerm().getIntent().setRecentTurns(0);
        properties.getShortTerm().getAgentRuntime().setMaxContextTokens(0);
        properties.getShortTerm().getIntent().setMaxContextTokens(-1);

        assertThat(properties.getShortTerm().cacheMessageLimit()).isEqualTo(2);
        assertThat(properties.getShortTerm().sourceMessageLimit()).isEqualTo(2);
        assertThat(properties.getShortTerm().getAgentRuntime().normalizedMaxContextTokens()).isEqualTo(1);
        assertThat(properties.getShortTerm().getIntent().normalizedMaxContextTokens()).isEqualTo(1);
    }

    private MemoryProperties enabledProperties() {
        MemoryProperties properties = new MemoryProperties();
        properties.getShortTerm().setEnabled(true);
        properties.getShortTerm().getAgentRuntime().setMaxContextTokens(1000);
        properties.getShortTerm().getIntent().setMaxContextTokens(1000);
        return properties;
    }

    private ShortTermMemoryContextAssembler assembler(MemoryProperties properties) {
        return new ShortTermMemoryContextAssembler(properties, messages -> messages.stream()
                .mapToInt(message -> message.content().codePointCount(0, message.content().length()))
                .sum());
    }

    private MemoryContext memory(List<ChatMessage> source,
                                 List<ConversationMemoryMessage> runtimeMessages,
                                 RouteMemoryContext routeMemory) {
        return new MemoryContext(source, List.of(), routeMemory, true, runtimeMessages);
    }

    private RouteMemoryContext routeMemory(String sourceRunId) {
        return new RouteMemoryContext(
                "user_correction",
                List.of(Map.of(
                        "type", "route",
                        "query", "route query",
                        "intent", "finance_data_query",
                        "routeAction", "ROUTE_SINGLE")),
                Map.of(),
                sourceRunId);
    }

    @SuppressWarnings("unchecked")
    private List<ConversationMemoryMessage> intentMessages(RouteMemoryContext context) {
        Object value = context.history().getFirst().get(ShortTermMemoryContextAssembler.INTENT_MESSAGES_KEY);
        return (List<ConversationMemoryMessage>) value;
    }

    private ChatMessage message(String id,
                                String parentMessageId,
                                long nodeOrder,
                                String runId,
                                String role,
                                String content) {
        return new ChatMessage(
                id,
                "tenant-1",
                "user-1",
                "session-1",
                parentMessageId,
                nodeOrder,
                Math.toIntExact(nodeOrder - 1),
                0,
                role,
                content,
                null,
                runId,
                "NORMAL",
                false,
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH.plusSeconds(nodeOrder));
    }
}
