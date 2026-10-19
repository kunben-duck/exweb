/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import java.util.List;
import java.util.Map;

/** DomainAgent仅识别正文及指定业务卡片；其他来源保持原口径，Intent结果由调用边界记录。 */
public final class DefaultFirstBusinessFrameClassifier implements FirstBusinessFrameClassifier {
    @Override
    public Classification classify(ChatEvent event) {
        if (event == null || event.payload() == null
                || event.payload().containsKey("candidateSwitchReplay")) {
            return Classification.NONE;
        }
        if ("domain-agent".equals(event.payload().get("source"))
                || "domain-agent-content".equals(event.payload().get("sourceType"))) {
            return domainAgent(event);
        }
        return switch (event.type()) {
            case "message.delta", "message.snapshot" ->
                    text(event.payload().get("text")) || text(event.payload().get("content"))
                            || text(event.payload().get("delta")) ? Classification.BUSINESS : Classification.NONE;
            case "runtime.card", "runtime.reference" -> event.payload().isEmpty()
                    ? Classification.NONE : Classification.BUSINESS;
            default -> Classification.NONE;
        };
    }

    private Classification domainAgent(ChatEvent event) {
        Map<String, Object> payload = event.payload();
        // content已拆为正文delta和思考事件，只在正文来源上采样，不读取思考中的同名字段。
        return switch (event.type()) {
            case "message.delta" -> "domain-agent-content".equals(payload.get("sourceType"))
                    && text(payload.get("delta")) ? Classification.BUSINESS : Classification.NONE;
            case "runtime.card" -> cardValue(payload.get("diyCardScene")) || cardValue(payload.get("contentAgent"))
                    ? Classification.BUSINESS : Classification.NONE;
            default -> Classification.NONE;
        };
    }

    private boolean cardValue(Object value) {
        return text(value) || value instanceof Map<?, ?> map && !map.isEmpty()
                || value instanceof List<?> list && !list.isEmpty();
    }

    private boolean text(Object value) {
        return value instanceof String string && !string.isBlank();
    }
}
