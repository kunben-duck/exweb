package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;

public interface FinanceChatFacade { Flux<ChatEvent> chat(ChatCommand command); }
