package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

/** 将 ChatService 模式快照转换为特定 Runtime provider 的协议参数。 */
public interface AgentModeAdapter {
    boolean supports(String provider);

    AgentModeOutboundParameters adapt(AgentModeProfile profile, String provider, String targetId);

    default boolean fallback() {
        return false;
    }
}
