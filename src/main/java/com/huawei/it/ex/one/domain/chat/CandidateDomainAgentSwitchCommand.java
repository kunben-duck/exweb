/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端从Intent候选中选择DomainAgent后发起的立即切换命令。
 *
 * <p>原始query和附件不由该命令携带，应用层会根据source run关联的可信user消息恢复。</p>
 */
public record CandidateDomainAgentSwitchCommand(
        String sourceRunId,
        String messageId,
        String skillId,
        Map<String, Object> metadata,
        AgentModeProfile agentMode,
        String intentAccessName
) {
    public CandidateDomainAgentSwitchCommand {
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(metadata));
    }
}
