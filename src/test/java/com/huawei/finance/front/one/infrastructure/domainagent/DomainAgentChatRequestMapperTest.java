package com.huawei.finance.front.one.infrastructure.domainagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.DomainAgentProperties;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.document.DocumentSource;
import com.huawei.finance.front.one.domain.document.DocumentStatus;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainAgentChatRequestMapperTest {
    private final DomainAgentProperties properties = new DomainAgentProperties();
    private final DomainAgentChatRequestMapper mapper =
            new DomainAgentChatRequestMapper(new ObjectMapper(), properties);

    @Test
    @SuppressWarnings("unchecked")
    void preservesSceneParamExtensionFieldsAndOverridesDocListWithTrustedDocuments() {
        DomainAgentRequest request = request(Map.of(
                "domainAgent", Map.of(
                        "sceneParam", Map.of(
                                "taxYear", "2026",
                                "regionCode", "CN-SZ",
                                "docList", List.of(Map.of("docId", "forged-doc"))
                        )
                )
        ), List.of(domainAgentDocument()));

        Map<String, Object> wire = mapper.toWireRequest(request);

        assertThat(wire)
                .containsEntry("isThinking", 1)
                .containsEntry("platform", "PC")
                .containsEntry("qaType", "normalQa")
                .containsEntry("query", "hello")
                .containsEntry("sessionId", "session1")
                .containsEntry("skillId", "skill-tax")
                .containsEntry("streamFlag", "stream")
                .containsEntry("supMsg", "");
        assertThat(wire).doesNotContainKeys("isThink", "queryType", "steamFlag");

        Map<String, Object> sceneParam = (Map<String, Object>) wire.get("sceneParam");
        assertThat(sceneParam)
                .containsEntry("taxYear", "2026")
                .containsEntry("regionCode", "CN-SZ");

        List<Map<String, Object>> docList = (List<Map<String, Object>>) sceneParam.get("docList");
        assertThat(docList).hasSize(1);
        assertThat(docList.get(0))
                .containsEntry("docId", "domain-doc-001")
                .containsEntry("docName", "invoice.pdf")
                .containsEntry("docRelativePath", "/domain/invoice.pdf")
                .containsEntry("docSize", 19800L)
                .containsEntry("levelCode", "IP");
    }

    @Test
    void rejectsNonObjectSceneParam() {
        DomainAgentRequest request = request(Map.of(
                "domainAgent", Map.of("sceneParam", "bad-scene-param")
        ), List.of());

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domainAgent.sceneParam");
    }

    @Test
    void rejectsUrlOnlyDomainAgentDocumentForDocList() {
        DomainAgentRequest request = request(Map.of(), List.of(urlOnlyDomainAgentDocument()));

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少可用于 DomainAgent 调用的 docId");
    }

    private DomainAgentRequest request(Map<String, Object> metadata, List<UploadedDocument> documents) {
        return new DomainAgentRequest(
                new UserContext("tenant1", "user1", "User One"),
                "session1",
                "run1",
                "skill-tax",
                "hello",
                documents,
                metadata,
                RuntimeForwardHeaders.empty()
        );
    }

    private UploadedDocument domainAgentDocument() {
        Instant now = Instant.parse("2026-06-11T00:00:00Z");
        return new UploadedDocument(
                "doc1",
                "tenant1",
                "user1",
                "session1",
                "invoice.pdf",
                "domain-agent",
                "domain-doc-001",
                "application/pdf",
                19800L,
                DocumentStatus.AVAILABLE.name(),
                DocumentSource.DOMAIN_AGENT_UPLOAD.name(),
                null,
                """
                        {
                          "providerCode": "domain-agent",
                          "providerDocument": {
                            "docId": "domain-doc-001",
                            "docName": "invoice.pdf",
                            "docRelativePath": "/domain/invoice.pdf",
                            "docSize": 19800,
                            "levelCode": "IP"
                          }
                        }
                        """,
                now,
                now
        );
    }

    private UploadedDocument urlOnlyDomainAgentDocument() {
        Instant now = Instant.parse("2026-06-11T00:00:00Z");
        return new UploadedDocument(
                "doc-url",
                "tenant1",
                "user1",
                "session1",
                "invoice.pdf",
                "domain-agent",
                "domain-agent-url:abcd",
                "application/pdf",
                19800L,
                DocumentStatus.AVAILABLE.name(),
                DocumentSource.DOMAIN_AGENT_UPLOAD.name(),
                null,
                """
                        {
                          "providerCode": "domain-agent",
                          "providerDocument": {
                            "providerLocatorType": "URL",
                            "url": "https://domain.example/files/invoice.pdf",
                            "docName": "invoice.pdf",
                            "docSize": 19800
                          }
                        }
                        """,
                now,
                now
        );
    }
}
