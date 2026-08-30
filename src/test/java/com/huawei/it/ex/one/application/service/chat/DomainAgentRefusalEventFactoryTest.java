/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class DomainAgentRefusalEventFactoryTest {
    @Test
    void sameRunRerouteKeepsFrontendIntentAccessName() {
        ChatCommand command = new ChatCommand(
                "cmd1", "tenant1", "user1", "session1", null, "web", "分析资金情况",
                List.of(), Map.of("scene", "fund"), null, null, ChatRunMode.NEXT,
                null, null, null, null, null, null, null, Map.of(), null, null,
                null, null, null, "finance-pc-entry");

        ChatCommand reroute = new DomainAgentRefusalEventFactory().commandWithDomainRejectContext(
                command,
                new DomainAgentRejectReason("原技能拒答", "原意图"));

        assertThat(reroute.intentAccessName()).isEqualTo("finance-pc-entry");
        assertThat(reroute.metadata()).doesNotContainKey("intentAccessName");
    }
}
