package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.interfaces.chat.dto.FrontChatEventDto;
import org.springframework.stereotype.Component;

/**
 * 领域事件到前端事件 DTO 的翻译器。
 *
 * <p>对外隐藏领域事件实现类，只暴露稳定的 type、payload 和 IM 消息类型。</p>
 */
@Component
public class ChatEventTranslator {
    public FrontChatEventDto toDto(ChatEvent event) {
        return new FrontChatEventDto(event.runId(), event.sessionId(), event.sequence(), event.type(), toImMessageType(event), event.payload());
    }

    private String toImMessageType(ChatEvent event) {
        // 当前只有 message.delta 属于助手文本输出，其余状态类事件统一视为 system 消息。
        return "message.delta".equals(event.type()) ? "text" : "system";
    }
}
