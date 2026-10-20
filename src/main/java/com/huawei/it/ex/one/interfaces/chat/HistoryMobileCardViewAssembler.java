/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessagePartDto;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only history overlay; persisted Parts, cached messages and stream payloads stay unchanged. */
@Component
public class HistoryMobileCardViewAssembler {
    private static final AppLogger log = AppLoggerFactory.getLogger(HistoryMobileCardViewAssembler.class);
    private final DomainAgentProperties properties;
    private final ObjectMapper objectMapper;

    public HistoryMobileCardViewAssembler(DomainAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<ChatMessagePartDto> enrich(ChatMessage message, List<ChatMessagePartDto> parts) {
        try {
            if (!properties.isHistoryMobileCardFallbackEnabled() || parts.isEmpty()
                    || !"assistant".equals(message.role())) {
                return parts;
            }
            Map<String, String> urls = properties.getHistoryMobileCardUrls();
            if (urls == null || urls.isEmpty() || parts.stream().noneMatch(this::isUrlCard)) {
                return parts;
            }
            String skillId = MessageSkillContext.messageSkillId(objectMapper, message.metadataJson());
            String mobileUrl = skillId == null ? null : urls.get(skillId);
            if (mobileUrl == null || mobileUrl.isBlank()) {
                return parts;
            }
            return fillParts(parts, skillId, mobileUrl.trim());
        } catch (RuntimeException failure) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "History mobile card fallback failed; returning original parts")
                    .operation("chat-history.mobile-card-fallback")
                    .attribute("failureType", failure.getClass().getName()).build());
            return parts;
        }
    }

    private List<ChatMessagePartDto> fillParts(List<ChatMessagePartDto> parts, String skillId, String mobileUrl) {
        List<ChatMessagePartDto> result = null;
        for (int i = 0; i < parts.size(); i++) {
            ChatMessagePartDto part = parts.get(i);
            if (!isUrlCard(part) || !missingText(part.payload().get("mobileCardUrl"))
                    || !matchesSkill(part.payload().get("domainAgentId"), skillId)) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(parts);
            }
            Map<String, Object> payload = new LinkedHashMap<>(part.payload());
            payload.put("mobileCardUrl", mobileUrl);
            result.set(i, new ChatMessagePartDto(part.partId(), part.messageId(), part.runId(),
                    part.partType(), part.sourceType(), part.contentText(), part.title(), part.status(),
                    part.channel(), part.displayHint(), part.visible(), ChatPayloadMaps.immutableCopy(payload),
                    part.partOrder(), part.createdAt()));
        }
        return result == null ? parts : List.copyOf(result);
    }

    private boolean isUrlCard(ChatMessagePartDto part) {
        return "CARD".equals(part.partType()) && part.payload() != null
                && "domain-agent".equals(part.payload().get("source"))
                && part.payload().get("cardUrl") instanceof String url && !url.isBlank();
    }

    private boolean missingText(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private boolean matchesSkill(Object value, String skillId) {
        return missingText(value) || skillId.equals(value);
    }
}
