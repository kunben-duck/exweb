package com.huawei.it.ex.one.application.service.agentdatapersistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.domain.document.UploadedDocument;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class DomainAgentAttachmentTypeValidatorTest {
    private final DomainAgentAttachmentTypeValidator validator = new DomainAgentAttachmentTypeValidator();

    @Test
    void parsesConcatenatedConfigurationAndComparesCaseInsensitively() {
        DomainAgentAttachmentTypeValidator.Validation validation = validator.validate(
                configuration(".xlsx.xls;.rar;.zip"),
                List.of(document("sheet.XLSX"), document("archive.RAR"), document("report.PDF")));

        assertThat(validation.status()).isEqualTo(
                DomainAgentAttachmentTypeValidator.Status.UNSUPPORTED);
        assertThat(validation.supportedTypes())
                .containsExactly(".xlsx", ".xls", ".rar", ".zip");
        assertThat(validation.unsupportedTypes()).containsExactly(".pdf");
        assertThat(validation.unsupportedAttachments()).singleElement()
                .satisfies(value -> {
                    assertThat(value.name()).isEqualTo("report.PDF");
                    assertThat(value.extension()).isEqualTo(".pdf");
                });
    }

    @Test
    void blankConfigurationAndExtensionlessFilesAreAllowed() {
        assertThat(validator.validate(configuration(null), List.of(document("report.pdf"))).status())
                .isEqualTo(DomainAgentAttachmentTypeValidator.Status.ALLOWED);
        assertThat(validator.validate(configuration(".pdf"), List.of(document("README"))).status())
                .isEqualTo(DomainAgentAttachmentTypeValidator.Status.ALLOWED);
        assertThat(validator.requiresConfiguration(List.of(document(".env")))).isFalse();
    }

    @Test
    void malformedNonBlankConfigurationFailsOpen() {
        DomainAgentAttachmentTypeValidator.Validation validation = validator.validate(
                configuration("xlsx;pdf"), List.of(document("report.pdf")));

        assertThat(validation.status()).isEqualTo(
                DomainAgentAttachmentTypeValidator.Status.MALFORMED_CONFIGURATION);
        assertThat(validation.unsupported()).isFalse();
    }

    private DomainAgentSkillConfiguration configuration(String attachmentType) {
        return new DomainAgentSkillConfiguration(
                "skill-1", "技能一", Boolean.TRUE, attachmentType);
    }

    private UploadedDocument document(String name) {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        return new UploadedDocument(
                "doc-" + name, "tenant-1", "user-1", "session-1", name,
                "bucket", "object-key", "application/octet-stream", 10,
                "AVAILABLE", "LOCAL_UPLOAD", null, null, now, now);
    }
}
