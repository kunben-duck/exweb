package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class IntentServiceRequestMapperTest {
    @Test
    void agentModeNeverEntersIntentWireRequest() {
        ChatCommand command = new ChatCommand(
                "cmd1", "tenant1", "user1", "session1", null, "web", "分析资金情况",
                List.of(), Map.of("scene", "fund"), null, null, ChatRunMode.NEXT,
                null, null, null, null, null, null, null, Map.of(), null, null,
                new AgentModeProfile(List.of(new AgentModeSelection("thinking", "deep", "深度思考"))));
        IntentServiceRequestMapper mapper = new IntentServiceRequestMapper(new IntentServiceHttpProperties());

        IntentRecognizeRequest request = mapper.toWireRequest(
                command, MemoryContext.empty(), new UserContext("tenant1", "user1", "User One"));

        assertThat(request.query()).isEqualTo("分析资金情况");
        assertThat(new ObjectMapper().valueToTree(request).has("agentMode")).isFalse();
    }
}
