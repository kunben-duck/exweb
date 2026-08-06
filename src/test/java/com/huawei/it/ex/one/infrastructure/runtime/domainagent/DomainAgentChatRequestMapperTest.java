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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class DomainAgentChatRequestMapperTest {
    private final DomainAgentProperties properties = new DomainAgentProperties();
    private final DomainAgentChatRequestMapper mapper =
            new DomainAgentChatRequestMapper(new ObjectMapper(), properties);

    @Test
    @SuppressWarnings("unchecked")
    void forwardsMetadataExtensionsButOverridesReservedBindingFields() {
        Map<String, Object> metadata = Map.of(
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
    void rejectsAttachmentsWithoutMetadataDocList() {
        DomainAgentRequest request = request(Map.of("skillId", "skill-tax"), List.of(domainAgentDocument()));

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata.sceneParam");
    }

    @Test
    void rejectsForgedDocIdNotOwnedByAttachments() {
        DomainAgentRequest request = request(Map.of(
                "sceneParam", Map.of("docList", List.of(Map.of("docId", "forged-doc")))
        ), List.of(domainAgentDocument()));

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未授权文档引用");
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
    void rejectsDocListWhenAttachmentReferencesAreMissing() {
        DomainAgentRequest request = request(Map.of(
                "sceneParam", Map.of("docList", List.of(Map.of("docId", "domain-doc-001")))
        ), List.of());

        assertThatThrownBy(() -> mapper.toWireRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attachments");
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
