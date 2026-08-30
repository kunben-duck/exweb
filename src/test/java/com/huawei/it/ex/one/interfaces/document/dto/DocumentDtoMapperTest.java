/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.document.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.document.DocumentStatus;
import com.huawei.it.ex.one.domain.document.UploadedDocument;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class DocumentDtoMapperTest {
    private final DocumentDtoMapper mapper = new DocumentDtoMapper(new ObjectMapper());

    @Test
    void parsesMetadataJsonAsStructuredResponseField() {
        UploadedDocument document = new UploadedDocument(
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
                "EDM_UPLOAD",
                null,
                """
                        {
                          "providerCode": "domain-agent",
                          "providerDocument": {
                            "docId": "domain-doc-001",
                            "levelCode": "IP"
                          }
                        }
                        """,
                Instant.parse("2026-06-11T00:00:00Z"),
                Instant.parse("2026-06-11T00:00:00Z")
        );

        UploadedDocumentDto dto = mapper.toDto(document);

        assertThat(dto.source()).isEqualTo("EDM_UPLOAD");
        assertThat(dto.metadataJson().get("providerCode").asText()).isEqualTo("domain-agent");
        assertThat(dto.metadataJson().get("providerDocument").get("docId").asText()).isEqualTo("domain-doc-001");
        assertThat(dto.metadataJson().get("providerDocument").get("levelCode").asText()).isEqualTo("IP");
    }
}
