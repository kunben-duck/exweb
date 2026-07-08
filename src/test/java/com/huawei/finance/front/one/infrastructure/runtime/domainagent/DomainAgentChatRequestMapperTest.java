package com.huawei.finance.front.one.infrastructure.runtime.domainagent;

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
    void forwardsMetadataExtensionsButOverridesReservedBindingFields() {
        Map<String, Object> metadata = Map.of(
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

    private DomainAgentRequest request(Map<String, Object> metadata, List<UploadedDocument> documents) {
        return new DomainAgentRequest(
                new UserContext("tenant1", "user1", "User One"),
                "session1",
                "run1",
                "skill-tax",
                "session1",
                "hello",
                documents,
                metadata,
                RuntimeForwardHeaders.empty()
        );
    }

    private UploadedDocument domainAgentDocument() {
        return document(
                "doc1",
                "domain-doc-001",
                DocumentSource.DOMAIN_AGENT_UPLOAD.name(),
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
