package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatEventDto;
import org.springframework.stereotype.Component;

/**
 * 领域事件到前端事件 DTO 的翻译器。
 *
 * <p>对外隐藏领域事件实现类，只暴露 GPT-like 流式协议稳定需要的 runId、sessionId、
 * seq、type 和 payload。</p>
 */
@Component
public class ChatEventTranslator {
    /**
     * 将领域聊天事件转换为前端稳定 DTO。
     *
     * @param event 已持久化或待输出的领域聊天事件。
     * @return 前端事件 DTO。
     */
    public ChatEventDto toDto(ChatEvent event) {
        return new ChatEventDto(event.runId(), event.sessionId(), event.sequence(), event.type(), event.payload());
    }
}
