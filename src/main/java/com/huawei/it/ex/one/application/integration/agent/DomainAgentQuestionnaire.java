/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import java.util.Map;

/** DomainAgent 问卷边界；同形状的 Relay 卡片不能进入 DomainAgent 续跑。 */
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
