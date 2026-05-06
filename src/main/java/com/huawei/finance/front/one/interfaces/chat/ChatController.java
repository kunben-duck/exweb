package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.interfaces.chat.dto.FrontChatEventDto;
import com.huawei.finance.front.one.interfaces.chat.dto.FrontChatRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 聊天 HTTP 接口。
 *
 * <p>SSE 和 NDJSON 共用同一套请求翻译与事件翻译逻辑，只在传输格式上区分。</p>
 */
@RestController
@RequestMapping("/api/v1/finance/chat")
public class ChatController {
    private final FinanceChatFacade chatFacade;
    private final ChatRequestTranslator requestTranslator;
    private final ChatEventTranslator eventTranslator;
    public ChatController(FinanceChatFacade chatFacade, ChatRequestTranslator requestTranslator, ChatEventTranslator eventTranslator) {
        this.chatFacade = chatFacade; this.requestTranslator = requestTranslator; this.eventTranslator = eventTranslator;
    }
    @PostMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<FrontChatEventDto>> chatSse(@RequestBody FrontChatRequest request) {
        // SSE 事件名直接复用领域事件 type，方便前端按事件类型分发处理。
        return chatFacade.chat(requestTranslator.toCommand(request, "sse"))
                .map(eventTranslator::toDto)
                .map(dto -> ServerSentEvent.<FrontChatEventDto>builder().event(dto.type()).data(dto).build());
    }
    @PostMapping(value = "/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<FrontChatEventDto> chatStream(@RequestBody FrontChatRequest request) {
        // NDJSON 适合不支持 SSE 的网关或客户端，事件结构与 SSE data 保持一致。
        return chatFacade.chat(requestTranslator.toCommand(request, "http_stream")).map(eventTranslator::toDto);
    }
}
