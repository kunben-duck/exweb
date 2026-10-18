/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

/** 默认仅识别有效正文、业务卡片和引用；Intent结果由调用边界单独记录。 */
public final class DefaultFirstBusinessFrameClassifier implements FirstBusinessFrameClassifier {
    @Override
    public Classification classify(ChatEvent event) {
        if (event == null || event.payload() == null
                || event.payload().containsKey("candidateSwitchReplay")) {
            return Classification.NONE;
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

    private boolean text(Object value) {
        return value instanceof String string && !string.isBlank();
    }
}
