/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeInteractionDispatchState;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentChatRequestMapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

class AgentApiDocumentationTest {
    private static final Path FORWARDING = Path.of("docs/agent-runtime-forwarding.md");
    private final ObjectMapper json = new ObjectMapper();
    private final DomainAgentChatRequestMapper mapper = new DomainAgentChatRequestMapper(new DomainAgentProperties());

    @Test
    void openApiReferencesAndOperationIdsRemainValid() throws Exception {
        JsonNode api = openApi();
        List<String> operationIds = new ArrayList<>();
        validateReferences(api, api, operationIds);
        assertThat(operationIds).isNotEmpty().doesNotHaveDuplicates();
        JsonNode paths = api.path("paths");
        assertThat(paths.path("/v1/chat/sessions/{sessionId}/promote").path("post").has("requestBody")).isFalse();
        JsonNode switchExamples = paths.path("/v1/chat/runs/{sourceRunId}/switch-domain-agent").path("post")
                .path("requestBody").path("content").path("application/json").path("examples");
        assertThat(switchExamples.path("relayExpert").path("value").path("skillId").asText())
                .isEqualTo("RE_national-cfo");
        assertThat(paths.path("/v1/chat/runs").path("post").path("requestBody")
                .path("content").path("application/json").path("examples").has("refusalRetryCurrent")).isFalse();
        assertThat(api.path("components").path("schemas").path("CreateChatRunRequest")
                .path("properties").path("interactionAction").path("enum"))
                .isEqualTo(json.readTree("[\"AUTO_SELECT\"]"));
        assertThat(runExample(api, "refusalSwitchApprove").has("interactionAction")).isFalse();
    }

    @Test
    void forwardingJsonAndLocalFileLinksAreValid() throws Exception {
        String markdown = Files.readString(FORWARDING);
        var blocks = Pattern.compile("(?s)```json\\s+(.*?)```").matcher(markdown);
        int count = 0;
        while (blocks.find()) {
            assertThat(json.readTree(blocks.group(1))).isNotNull();
            count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(20);
        assertThat(documentedExamples()).hasSize(count);
        var links = Pattern.compile("\\]\\(([^)]+)\\)").matcher(markdown);
        while (links.find()) {
            String target = links.group(1).split("#", 2)[0];
            if (!target.isEmpty() && !target.contains(":")) {
                assertThat(FORWARDING.getParent().resolve(target).normalize()).exists();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "questionnaireIgnoreUser,domain-ignore-user",
            "questionnaireIgnoreTimeout,domain-ignore-timeout",
            "questionnaireIgnoreMixed,domain-ignore-mixed",
            "questionnairePartialAnswer,domain-partial",
            "questionnaireIgnoreOnly,domain-ignore-only"
    })
    void frontendExamplesProduceDocumentedDomainAgentRequests(String frontendName, String wireName) throws Exception {
        JsonNode front = runExample(openApi(), frontendName);
        Map<String, Object> response = json.convertValue(front, new TypeReference<>() { });
        var request = new AgentRuntimeInteractionResponseRequest("tenant_demo", "user_demo", "demo_account", null,
                "session_demo", "run_B", "domain_session_demo", "domain-agent", "interaction_questionnaire_001",
                "AGENT_CLARIFICATION", "approval_demo", response, RuntimeForwardHeaders.empty(), TraceContext.empty(),
                Map.of("userMessageId", "msg_user_demo", "skillId", "skill_A"), RuntimeInteractionDispatchState.tracked());
        assertThat(json.<JsonNode>valueToTree(mapper.toInteractionWireRequest(request)))
                .isEqualTo(documentedExamples().get(wireName));
    }

    @Test
    void documentedQueryAndRetryUseTrustedIdsWithoutNewControlFields() throws Exception {
        Map<String, JsonNode> examples = documentedExamples();
        JsonNode example = examples.get("domain-query");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sceneParam", json.convertValue(example.path("sceneParam"), new TypeReference<>() { }));
        metadata.put("bizContext", json.convertValue(example.path("bizContext"), new TypeReference<>() { }));
        metadata.put("runId", "forged");
        metadata.put("messageId", "forged");
        metadata.put("sessionId", "forged");
        metadata.put("skillId", "forged");
        metadata.put("query", "forged");
        var user = new UserContext("tenant_demo", "user_demo", "Demo");
        var request = new DomainAgentRequest(user, "session_demo", "run_A", "skill_A", "domain_session_demo",
                "分析本月费用", "msg_user_demo", List.of(), List.of(
                        new ConversationMemoryMessage("user", "上月费用是多少", "skill_history"),
                        new ConversationMemoryMessage("assistant", "上月费用为示例值", "skill_history")),
                true, metadata, RuntimeForwardHeaders.empty());
        assertThat(json.<JsonNode>valueToTree(mapper.toWireRequest(request))).isEqualTo(example);
    }

    private JsonNode openApi() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(4_000_000);
        Object api = new Yaml(new SafeConstructor(options)).load(
                Files.readString(Path.of("docs/openapi/financeex-chatservice-v1.yaml")));
        return json.valueToTree(api);
    }

    @Test
    void documentedIntentContextMatchesTheRequestMapper() throws Exception {
        var context = new com.huawei.it.ex.one.domain.memory.RouteMemoryContext("domain_reject",
                List.of(Map.of("type", "route", "query", "查询销售指标口径", "intent", "财经知识助手",
                        "intentId", "intent_knowledge_001")),
                Map.of("lastIntent", "财经知识助手", "lastIntentId", "intent_knowledge_001",
                        "domainRejectMessage", "该问题需要销售数据分析能力"));
        var requestMapper = new com.huawei.it.ex.one.infrastructure.intent.IntentServiceRequestMapper(
                new com.huawei.it.ex.one.infrastructure.intent.IntentServiceHttpProperties());
        var wire = requestMapper.toWireRequest(null,
                com.huawei.it.ex.one.domain.memory.MemoryContext.empty().withRouteMemory(context), null);
        assertThat(json.<JsonNode>valueToTree(wire.conversationContext()))
                .isEqualTo(documentedExamples().get("intent-reject-context"));
        var blocks = Pattern.compile("(?s)```json\\s+(.*?)```")
                .matcher(Files.readString(Path.of("docs/intent/intent.md")));
        while (blocks.find()) {
            assertThat(json.readTree(blocks.group(1))).isNotNull();
        }
    }

    private JsonNode runExample(JsonNode api, String name) {
        JsonNode value = api.path("paths").path("/v1/chat/runs").path("post").path("requestBody")
                .path("content").path("application/json").path("examples").path(name).path("value");
        assertThat(value.isMissingNode()).as(name).isFalse();
        return value;
    }

    private Map<String, JsonNode> documentedExamples() throws Exception {
        var blocks = Pattern.compile("(?s)<!-- contract: ([a-z-]+) -->\\s+```json\\s+(.*?)```")
                .matcher(Files.readString(FORWARDING));
        Map<String, JsonNode> examples = new LinkedHashMap<>();
        while (blocks.find()) {
            assertThat(examples.put(blocks.group(1), json.readTree(blocks.group(2)))).isNull();
        }
        return examples;
    }

    private void validateReferences(JsonNode node, JsonNode root, List<String> operationIds) {
        if (node.has("$ref")) {
            String reference = node.path("$ref").asText();
            assertThat(reference).startsWith("#/");
            assertThat(root.at(reference.substring(1)).isMissingNode()).as(reference).isFalse();
        }
        if (node.has("operationId")) {
            operationIds.add(node.path("operationId").asText());
        }
        node.forEach(child -> validateReferences(child, root, operationIds));
    }
}
