package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.memory.ShortTermMemoryContextAssembler;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class IntentClarificationContextAssemblerTest {
    private final IntentClarificationContextAssembler assembler = new IntentClarificationContextAssembler();
    private final UserContext user = new UserContext("tenant-1", "user-1", "User One");
    private final ChatSession session = new ChatSession(
            "session-1", user.tenantId(), user.ownerUserId(),
            "测试会话", "ACTIVE", "web", Instant.now(), Instant.now());

    @Test
    void domainRejectReasonSurvivesMultipleClarificationTurns() {
        Map<String, Object> rerouteContext = rerouteContext(Map.of(
                "lastIntent", "财经智能问数",
                "domainRejectMessage", "当前请求不属于问数能力范围"));
        ChatCommand first = command(
                requestPayload("原始问题", "原始问题", "需要分析哪个方向？",
                        List.of(), rerouteContext),
                "原因分析");

        assertThat(first.metadata())
                .containsEntry("routeTrigger", "clarify_answer")
                .containsEntry("lastIntentRejectReason", Map.of(
                        "lastIntent", "财经智能问数",
                        "domainRejectMessage", "当前请求不属于问数能力范围"));

        Map<String, Object> firstClarification = map(first.metadata().get("intentClarification"));
        ChatCommand second = command(
                requestPayload(
                        "原始问题",
                        first.message(),
                        "需要分析哪个地区？",
                        listOfMaps(firstClarification.get("clarificationHistory")),
                        map(first.metadata().get("domainAgentRerouteContext"))),
                "广东地区");

        assertThat(second.metadata())
                .containsEntry("routeTrigger", "clarify_answer")
                .containsEntry("lastIntentRejectReason", Map.of(
                        "lastIntent", "财经智能问数",
                        "domainRejectMessage", "当前请求不属于问数能力范围"));
        assertThat(listOfMaps(map(second.metadata().get("intentClarification"))
                .get("clarificationHistory"))).hasSize(2);
    }

    @Test
    void legacyDomainRejectContextRestoresReasonWithUnknownIntent() {
        ChatCommand command = command(
                requestPayload(
                        "原始问题",
                        "原始问题",
                        "请补充场景",
                        List.of(),
                        rerouteContext(null)),
                "补充信息");

        assertThat(command.metadata()).containsEntry("lastIntentRejectReason", Map.of(
                "lastIntent", "未知意图",
                "domainRejectMessage", "历史拒答原因"));
    }

    @Test
    void ordinaryClarificationDoesNotAddDomainRejectReason() {
        ChatCommand command = command(
                requestPayload("原始问题", "原始问题", "请补充场景", List.of(), null),
                "补充信息");

        assertThat(command.metadata())
                .containsEntry("routeTrigger", "clarify_answer")
                .doesNotContainKey("lastIntentRejectReason")
                .doesNotContainKey("domainAgentRerouteContext");
    }

    @Test
    void frozenIntentMessagesContinuePrivatelyWithoutEnteringPublicRequestSnapshot() {
        List<Map<String, Object>> frozen = List.of(Map.of("role", "user", "content", "历史追问"));
        Map<String, Object> requestPayload = new LinkedHashMap<>(
                requestPayload("原始问题", "原始问题", "请补充场景", List.of(), null));
        requestPayload.put(ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY, frozen);

        ChatCommand command = command(Map.copyOf(requestPayload), "补充信息");

        assertThat(command.metadata()).containsEntry(
                ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY, frozen);
        assertThat(map(map(command.metadata().get("intentClarification")).get("request")))
                .doesNotContainKey(ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY);
        assertThat(ShortTermMemoryContextAssembler.publicInteractionPayload(requestPayload))
                .doesNotContainKey(ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY);
    }

    private ChatCommand command(Map<String, Object> requestPayload, String answer) {
        ChatInteractionRequest interaction = new ChatInteractionRequest(
                "interaction-" + answer,
                user.tenantId(),
                user.ownerUserId(),
                session.id(),
                "run-source",
                null,
                "message-user",
                "message-assistant",
                "intent-agent",
                null,
                null,
                null,
                ChatInteractionType.INTENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                requestPayload,
                Map.of(),
                Instant.now().plus(Duration.ofHours(1)),
                null,
                null,
                Instant.now(),
                Instant.now());
        return assembler.command(
                user,
                session,
                interaction,
                Map.of("answerText", answer),
                new IntentClarificationContextAssembler.ContinuationInput(
                        answer,
                        answer,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        null));
    }

    private Map<String, Object> requestPayload(
            String originalQuery,
            String clarifyTriggerQuery,
            String clarifyQuestion,
            List<Map<String, Object>> history,
            Map<String, Object> rerouteContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originalQuery", originalQuery);
        payload.put("clarifyTriggerQuery", clarifyTriggerQuery);
        payload.put("clarifyQuestion", clarifyQuestion);
        payload.put("clarificationType", "MISSING_INFORMATION");
        if (!history.isEmpty()) {
            payload.put("clarificationHistory", history);
        }
        if (rerouteContext != null) {
            payload.put("domainAgentRerouteContext", rerouteContext);
        }
        return Map.copyOf(payload);
    }

    private Map<String, Object> rerouteContext(Map<String, Object> rejectReason) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("currentTargetId", "agent-a");
        context.put("refusalReason", "历史拒答原因");
        if (rejectReason != null) {
            context.put("lastIntentRejectReason", rejectReason);
        }
        return Map.copyOf(context);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source
                ? (Map<String, Object>) source
                : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> source
                ? (List<Map<String, Object>>) source
                : List.of();
    }
}
