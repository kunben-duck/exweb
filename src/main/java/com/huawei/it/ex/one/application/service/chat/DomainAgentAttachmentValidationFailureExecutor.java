package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将DomainAgent附件类型拒绝转换为稳定的结构化业务完成事件。 */
final class DomainAgentAttachmentValidationFailureExecutor {
    static final String FINISH_REASON = "ATTACHMENT_TYPE_UNSUPPORTED";

    Flux<ChatEvent> execute(String runId, String sessionId, Map<String, Object> commonPayload) {
        Map<String, Object> progress = new LinkedHashMap<>(commonPayload);
        progress.put("stage", "attachment_validation");
        progress.put("status", "FAILED");

        Map<String, Object> card = new LinkedHashMap<>(commonPayload);
        card.put("cardType", "domainAgentAttachmentUnsupported");
        card.put("cardSources", List.of("attachmentValidation"));

        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("finishReason", FINISH_REASON);
        completed.put("skillInvocationStarted", false);

        List<ChatEvent> events = new ArrayList<>(3);
        events.add(RuntimeEvent.progress(runId, sessionId, progress));
        events.add(RuntimeEvent.card(runId, sessionId, card));
        events.add(MessageCompletedEvent.of(runId, sessionId, completed));
        return Flux.fromIterable(events);
    }
}
