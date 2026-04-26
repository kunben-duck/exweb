package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;

public interface IntentRecognizer {
    IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user);
    String provider();
}
