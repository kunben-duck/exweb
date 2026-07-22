package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ConversationTurnStreamDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ConversationTurnStreamPayloadDto;
import com.huawei.it.ex.one.interfaces.chat.dto.EncodedChatEventItemDto;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * ChatService 标准事件到前端 turn stream 传输结构的翻译器。
 *
 * <p>领域层和事件事实源仍只理解 {@code ChatEventDto}；该翻译器只存在于接口层，用于把对前端的
 * WebSocket/Event Resume 响应包装成统一 conversation turn stream。这样后续可以扩展
 * heartbeat、done 或 turn 级状态，而不污染 {@code fin_ex_chat_event_t.event_type}。</p>
 */
@Component
public class ChatTurnStreamTranslator {
    /** 前端看到的 turn stream 外层类型。 */
    public static final String TURN_STREAM_TYPE = "conversation-turn-stream";
    /** encoded item 的编码版本；首版仍是完整 JSON DTO，不引入 JSON Patch。 */
    public static final String ENCODING = "chat-event-json-v1";

    /**
     * 将一个 ChatService 标准事件包装成 stream-item。
     *
     * @param event 已转换好的标准聊天事件 DTO。
     * @return 前端 WebSocket/SSE data 中的 conversation turn stream 片段。
     */
    public ConversationTurnStreamDto streamItem(ChatEventDto event) {
        EncodedChatEventItemDto encodedItem = new EncodedChatEventItemDto(ENCODING, event.type(), event);
        ConversationTurnStreamPayloadDto payload = new ConversationTurnStreamPayloadDto(
                "stream-item",
                event.sessionId(),
                event.runId(),
                streamItemId(event),
                Instant.now().toEpochMilli(),
                encodedItem,
                null,
                null
        );
        return new ConversationTurnStreamDto(TURN_STREAM_TYPE, payload);
    }

    /**
     * 构造 turn 级 heartbeat。heartbeat 不对应数据库事件，不推进前端 afterSeq。
     *
     * @param sessionId 会话标识。
     * @param runId run 标识。
     * @param lastSeq 服务端已发送或观察到的最新事件 seq。
     * @return heartbeat 片段。
     */
    public ConversationTurnStreamDto heartbeat(String sessionId, String runId, long lastSeq) {
        ConversationTurnStreamPayloadDto payload = new ConversationTurnStreamPayloadDto(
                "heartbeat",
                sessionId,
                runId,
                null,
                Instant.now().toEpochMilli(),
                null,
                lastSeq,
                null
        );
        return new ConversationTurnStreamDto(TURN_STREAM_TYPE, payload);
    }

    /**
     * 构造 turn 级 done。done 表示本轮 turn 的传输闭合，不对应新的持久化 event。
     *
     * @param sessionId 会话标识。
     * @param runId run 标识。
     * @param lastSeq 终态事件的 seq。
     * @param terminalEventType 导致 turn 闭合的终态事件类型。
     * @return done 片段。
     */
    public ConversationTurnStreamDto done(String sessionId, String runId, long lastSeq, String terminalEventType) {
        ConversationTurnStreamPayloadDto payload = new ConversationTurnStreamPayloadDto(
                "done",
                sessionId,
                runId,
                null,
                Instant.now().toEpochMilli(),
                null,
                lastSeq,
                terminalEventType
        );
        return new ConversationTurnStreamDto(TURN_STREAM_TYPE, payload);
    }

    /**
     * 判断 ChatService 标准事件是否代表 run 终态。
     *
     * @param event 标准聊天事件 DTO。
     * @return true 表示该事件之后本轮 turn 应发送 done。
     */
    public boolean isTerminal(ChatEventDto event) {
        if (event == null || event.type() == null) {
            return false;
        }
        return "run.completed".equals(event.type())
                || "run.failed".equals(event.type())
                || "run.cancelled".equals(event.type())
                || "run.waiting_user".equals(event.type());
    }

    private String streamItemId(ChatEventDto event) {
        return "evt_" + event.sequence();
    }
}
