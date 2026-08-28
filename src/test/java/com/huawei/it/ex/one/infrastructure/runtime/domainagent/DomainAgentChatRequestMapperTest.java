package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.document.DocumentSource;
import com.huawei.it.ex.one.domain.document.DocumentStatus;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class DomainAgentChatRequestMapperTest {
    private final DomainAgentProperties properties = new DomainAgentProperties();
    private final DomainAgentChatRequestMapper mapper =
            new DomainAgentChatRequestMapper(properties);

    @Test
    @SuppressWarnings("unchecked")
    void forwardsMetadataExtensionsButOverridesReservedBindingFields() {
        Map<String, Object> metadata = Map.of(
                "runId", "forged-run",
                "messageId", "forged-message",
                "skillId", "skill-tax",
                "query", "front query",
                "platform", "MOBILE",
                "sceneParam", Map.of(
                        "taxYear", "2026",
                        "docList", List.of(Map.of(
                                "docId", "domain-doc-001",
                                "docName", "front-name.pdf"
                        ))
                )
        );

        Map<String, Object> wire = mapper.toWireRequest(request(metadata, List.of(domainAgentDocument())));

        assertThat(wire)
                .containsEntry("runId", "run1")
                .containsEntry("messageId", "msg-user-1")
                .containsEntry("skillId", "skill-tax")
                .containsEntry("query", "hello")
                .containsEntry("sessionId", "session1")
                .containsEntry("platform", "MOBILE")
                .doesNotContainKeys("isThinking", "qaType", "streamFlag", "supMsg");
        Map<String, Object> sceneParam = (Map<String, Object>) wire.get("sceneParam");
        assertThat(sceneParam).containsEntry("taxYear", "2026");
        List<Map<String, Object>> docList = (List<Map<String, Object>>) sceneParam.get("docList");
        assertThat(docList).containsExactly(Map.of(
                "docId", "domain-doc-001",
                "docName", "front-name.pdf"
        ));
    }

    @Test
    void acceptsAttachmentsWithoutMetadataDocList() {
        DomainAgentRequest request = request(Map.of("skillId", "skill-tax"), List.of(domainAgentDocument()));

        assertThat(mapper.toWireRequest(request)).doesNotContainKey("sceneParam");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsDocListThatDoesNotMatchAttachments() {
        DomainAgentRequest request = request(Map.of(
                "sceneParam", Map.of("docList", List.of(Map.of("docId", "forged-doc")))
        ), List.of(domainAgentDocument()));

        Map<String, Object> wire = mapper.toWireRequest(request);

        Map<String, Object> sceneParam = (Map<String, Object>) wire.get("sceneParam");
        assertThat(sceneParam.get("docList"))
                .isEqualTo(List.of(Map.of("docId", "forged-doc")));
    }

    @Test
    void acceptsUrlDocumentWhenMetadataUrlMatchesAuthorizedAttachment() {
        DomainAgentRequest request = request(Map.of(
                "sceneParam", Map.of("docList", List.of(Map.of("url", "https://domain.example/files/invoice.pdf")))
        ), List.of(urlOnlyDocument()));

        Map<String, Object> wire = mapper.toWireRequest(request);

        assertThat(wire).containsKey("sceneParam");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsDocListWithoutAttachmentReferences() {
        DomainAgentRequest request = request(Map.of(
                "sceneParam", Map.of("docList", List.of(Map.of("docId", "domain-doc-001")))
        ), List.of());

        Map<String, Object> wire = mapper.toWireRequest(request);

        Map<String, Object> sceneParam = (Map<String, Object>) wire.get("sceneParam");
        assertThat(sceneParam.get("docList"))
                .isEqualTo(List.of(Map.of("docId", "domain-doc-001")));
    }

    @Test
    void acceptsMissingAndEmptyDocList() {
        assertThat(mapper.toWireRequest(request(Map.of(), List.of())))
                .doesNotContainKey("sceneParam");
        assertThat(mapper.toWireRequest(request(Map.of("sceneParam", Map.of()), List.of())))
                .containsEntry("sceneParam", Map.of());
        assertThat(mapper.toWireRequest(request(
                Map.of("sceneParam", Map.of("docList", List.of())), List.of())))
                .containsEntry("sceneParam", Map.of("docList", List.of()));
    }

    @Test
    void rejectsNullDocList() {
        Map<String, Object> sceneParam = new java.util.LinkedHashMap<>();
        sceneParam.put("docList", null);
        DomainAgentRequest request = request(Map.of("sceneParam", sceneParam), List.of());

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须是 JSON array");
    }

    @Test
    void rejectsNonArrayDocList() {
        DomainAgentRequest request = request(
                Map.of("sceneParam", Map.of("docList", "domain-doc-001")), List.of());

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须是 JSON array");
    }

    @Test
    void rejectsNonObjectDocListItem() {
        DomainAgentRequest request = request(
                Map.of("sceneParam", Map.of("docList", List.of("domain-doc-001"))), List.of());

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("每一项必须是 JSON object");
    }

    @Test
    void rejectsDocListItemWithoutDocIdOrUrl() {
        DomainAgentRequest request = request(
                Map.of("sceneParam", Map.of("docList", List.of(Map.of("docName", "invoice.pdf")))), List.of());

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须包含 docId 或 url");
    }

    @Test
    void enforcesDomainAgentAttachmentCountLimit() {
        properties.setMaxAttachments(1);
        DomainAgentRequest request = request(
                Map.of(), List.of(domainAgentDocument(), urlOnlyDocument()));

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("附件数量超过上限: 1");
    }

    @Test
    void enabledShortTermMemoryOverridesClientMessagesAtRoot() {
        DomainAgentRequest request = new DomainAgentRequest(
                new UserContext("tenant1", "user1", "User One"),
                "session1",
                "run1",
                "skill-tax",
                "session1",
                "hello",
                List.of(),
                List.of(
                        new ConversationMemoryMessage("user", "历史问题"),
                        new ConversationMemoryMessage("assistant", "历史回答")),
                true,
                Map.of("messages", List.of(Map.of("role", "user", "content", "forged"))),
                RuntimeForwardHeaders.empty());

        Map<String, Object> wire = mapper.toWireRequest(request);

        assertThat(wire.get("messages")).isEqualTo(List.of(
                new ConversationMemoryMessage("user", "历史问题"),
                new ConversationMemoryMessage("assistant", "历史回答")));
    }

    @Test
    void enabledShortTermMemorySendsEmptyMessagesArrayWithoutHistory() {
        DomainAgentRequest request = new DomainAgentRequest(
                new UserContext("tenant1", "user1", "User One"),
                "session1",
                "run1",
                "skill-tax",
                "session1",
                "hello",
                List.of(),
                List.of(),
                true,
                Map.of(),
                RuntimeForwardHeaders.empty());

        assertThat(mapper.toWireRequest(request)).containsEntry("messages", List.of());
    }

    @Test
    void disabledShortTermMemoryDoesNotAddMessagesField() {
        assertThat(mapper.toWireRequest(request(Map.of(), List.of())))
                .doesNotContainKey("messages");
    }

    @Test
    void missingTrustedMessageIdDoesNotForwardMetadataValue() {
        DomainAgentRequest request = new DomainAgentRequest(
                new UserContext("tenant1", "user1", "User One"),
                "session1",
                "run1",
                "skill-tax",
                "session1",
                "hello",
                List.of(),
                Map.of("messageId", "forged-message"),
                RuntimeForwardHeaders.empty());

        assertThat(mapper.toWireRequest(request)).doesNotContainKey("messageId");
    }

    private DomainAgentRequest request(Map<String, Object> metadata, List<UploadedDocument> documents) {
        return new DomainAgentRequest(
                new UserContext("tenant1", "user1", "User One"),
                "session1",
                "run1",
                "skill-tax",
                "session1",
                "hello",
                "msg-user-1",
                documents,
                List.of(),
                false,
                metadata,
                RuntimeForwardHeaders.empty()
        );
    }

    private UploadedDocument domainAgentDocument() {
        return document(
                "doc1",
                "domain-doc-001",
                DocumentSource.EDM_UPLOAD.name(),
                """
                        {
                          "providerCode": "api-store",
                          "providerDocument": {
                            "providerLocatorType": "DOC_ID",
                            "docId": "domain-doc-001",
                            "docName": "invoice.pdf",
                            "docRelativePath": "/domain/invoice.pdf",
                            "docSize": 19800,
                            "levelCode": "IP"
                          }
                        }
                        """
        );
    }

    private UploadedDocument urlOnlyDocument() {
        return document(
                "doc-url",
                "api-store-url:abcd",
                DocumentSource.S3_UPLOAD.name(),
                """
                        {
                          "providerCode": "api-store",
                          "providerDocument": {
                            "providerLocatorType": "URL",
                            "url": "https://domain.example/files/invoice.pdf",
                            "docName": "invoice.pdf",
                            "docSize": 19800
                          }
                        }
                        """
        );
    }

    private UploadedDocument document(String id, String objectKey, String source, String metadataJson) {
        Instant now = Instant.parse("2026-06-11T00:00:00Z");
        return new UploadedDocument(
                id,
                "tenant1",
                "user1",
                "session1",
                "invoice.pdf",
                "api-store",
                objectKey,
                "application/pdf",
                19800L,
                DocumentStatus.AVAILABLE.name(),
                source,
                null,
                metadataJson,
                now,
                now
        );
    }
}
