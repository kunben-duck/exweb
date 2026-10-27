/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import java.util.Map;

/** 识别 DomainAgent 问卷；即使字段结构相同，Relay 问卷卡片也不能按 DomainAgent 问卷处理。 */
public final class DomainAgentQuestionnaire {
    private DomainAgentQuestionnaire() {
    }

    public static boolean isRequest(ChatEvent event) {
        return event != null && "runtime.card".equals(event.type()) && isRequest(event.payload());
    }

    public static boolean isRequest(Map<String, Object> payload) {
        return payload != null && "domain-agent".equals(payload.get("source"))
                && "approval-request".equals(payload.get("sourceType"))
                && "questionnaire".equals(payload.get("operation_type"));
    }
}
