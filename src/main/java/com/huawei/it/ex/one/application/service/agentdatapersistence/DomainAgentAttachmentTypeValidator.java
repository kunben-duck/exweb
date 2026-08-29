package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.domain.document.UploadedDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用可信文件名校验DomainAgent技能声明的附件扩展名范围。 */
final class DomainAgentAttachmentTypeValidator {
    static final String SOURCE_TYPE = "domain-agent-attachment-validation";
    static final String UNSUPPORTED_CODE = "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED";
    private static final Pattern CONFIGURED_EXTENSION = Pattern.compile("\\.[\\p{L}\\p{N}]+",
            Pattern.UNICODE_CHARACTER_CLASS);

    boolean requiresConfiguration(List<UploadedDocument> documents) {
        return documents != null && documents.stream().anyMatch(document -> extension(document).isPresent());
    }

    Validation validate(
            DomainAgentSkillConfiguration configuration,
            List<UploadedDocument> documents) {
        if (!requiresConfiguration(documents)) {
            return Validation.allowed();
        }
        String configuredTypes = configuration == null ? null : configuration.attachmentType();
        if (configuredTypes == null || configuredTypes.isBlank()) {
            return Validation.allowed();
        }
        List<String> supportedTypes = configuredExtensions(configuredTypes);
        if (supportedTypes.isEmpty()) {
            return Validation.malformedConfiguration();
        }
        Set<String> supported = Set.copyOf(supportedTypes);
        List<UnsupportedAttachment> unsupported = new ArrayList<>();
        LinkedHashSet<String> unsupportedTypes = new LinkedHashSet<>();
        for (UploadedDocument document : documents) {
            extension(document).filter(value -> !supported.contains(value)).ifPresent(value -> {
                unsupportedTypes.add(value);
                unsupported.add(new UnsupportedAttachment(
                        document.id(), document.originalName(), value));
            });
        }
        return unsupported.isEmpty()
                ? Validation.allowed()
                : Validation.unsupported(supportedTypes, List.copyOf(unsupportedTypes), unsupported);
    }

    Map<String, Object> payload(
            String skillId,
            String skillName,
            Validation validation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", SOURCE_TYPE);
        payload.put("code", UNSUPPORTED_CODE);
        payload.put("skillId", skillId);
        payload.put("skillName", firstText(skillName, skillId));
        payload.put("supportedAttachmentTypes", validation.supportedTypes());
        payload.put("unsupportedAttachmentTypes", validation.unsupportedTypes());
        payload.put("unsupportedAttachments", validation.unsupportedAttachments().stream()
                .map(UnsupportedAttachment::toPayload)
                .toList());
        return Collections.unmodifiableMap(payload);
    }

    private List<String> configuredExtensions(String value) {
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        Matcher matcher = CONFIGURED_EXTENSION.matcher(value);
        while (matcher.find()) {
            extensions.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(extensions);
    }

    private java.util.Optional<String> extension(UploadedDocument document) {
        if (document == null || document.originalName() == null) {
            return java.util.Optional.empty();
        }
        String name = document.originalName().trim();
        int separator = name.lastIndexOf('.');
        if (separator <= 0 || separator == name.length() - 1) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(name.substring(separator).toLowerCase(Locale.ROOT));
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second;
    }

    record Validation(
            Status status,
            List<String> supportedTypes,
            List<String> unsupportedTypes,
            List<UnsupportedAttachment> unsupportedAttachments) {
        Validation {
            supportedTypes = supportedTypes == null ? List.of() : List.copyOf(supportedTypes);
            unsupportedTypes = unsupportedTypes == null ? List.of() : List.copyOf(unsupportedTypes);
            unsupportedAttachments = unsupportedAttachments == null
                    ? List.of()
                    : List.copyOf(unsupportedAttachments);
        }

        static Validation allowed() {
            return new Validation(Status.ALLOWED, List.of(), List.of(), List.of());
        }

        static Validation malformedConfiguration() {
            return new Validation(Status.MALFORMED_CONFIGURATION, List.of(), List.of(), List.of());
        }

        static Validation unsupported(
                List<String> supportedTypes,
                List<String> unsupportedTypes,
                List<UnsupportedAttachment> unsupportedAttachments) {
            return new Validation(Status.UNSUPPORTED, supportedTypes, unsupportedTypes, unsupportedAttachments);
        }

        boolean unsupported() {
            return status == Status.UNSUPPORTED;
        }

        boolean malformed() {
            return status == Status.MALFORMED_CONFIGURATION;
        }
    }

    enum Status {
        ALLOWED,
        UNSUPPORTED,
        MALFORMED_CONFIGURATION
    }

    record UnsupportedAttachment(String documentId, String name, String extension) {
        Map<String, Object> toPayload() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("documentId", documentId);
            value.put("name", name);
            value.put("extension", extension);
            return Collections.unmodifiableMap(value);
        }
    }
}
