package com.huawei.finance.front.one.application.integration.intent;

import com.huawei.finance.front.one.domain.chat.ChatEvent;

/**
 * IntentAgent 输出帧：要么是前端可见事件，要么是最终识别结果。
 */
public record IntentAgentRouteFrame(
        ChatEvent event,
        IntentAgentRouteResult result
) {
    public static IntentAgentRouteFrame event(ChatEvent event) {
        return new IntentAgentRouteFrame(event, null);
    }

    public static IntentAgentRouteFrame result(IntentAgentRouteResult result) {
        return new IntentAgentRouteFrame(null, result);
    }

    public boolean eventFrame() {
        return event != null;
    }

    public boolean resultFrame() {
        return result != null;
    }
}
