package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.application.service.ChatFeedbackService;
import com.huawei.it.ex.one.chat.application.service.MessageFeedbackCommand;
import com.huawei.it.ex.one.chat.domain.ChatMessageFeedback;
import com.huawei.it.ex.one.chat.interfaces.dto.MessageFeedbackDto;
import com.huawei.it.ex.one.chat.interfaces.dto.MessageFeedbackRequest;
import com.huawei.it.ex.one.security.domain.UserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP endpoints for assistant-message feedback. */
@RestController
@RequestMapping("/v1/chat")
public class ChatFeedbackController {
    private final ChatFeedbackService feedbackService;
    private final ChatRequestContextResolver requestContextResolver;
    private final ChatFeedbackViewAssembler viewAssembler;

    public ChatFeedbackController(
            ChatFeedbackService feedbackService,
            ChatRequestContextResolver requestContextResolver,
            ChatFeedbackViewAssembler viewAssembler) {
        this.feedbackService = feedbackService;
        this.requestContextResolver = requestContextResolver;
        this.viewAssembler = viewAssembler;
    }

    @PostMapping(value = "/messages/{messageId}/feedback")
    public Mono<MessageFeedbackDto> feedback(
            @PathVariable("messageId") String messageId,
            @RequestBody MessageFeedbackRequest request) {
        UserContext user = requestContextResolver.resolveChatUser();
        return Mono.fromCallable(() -> {
                    ChatMessageFeedback feedback = feedbackService.submit(user, new MessageFeedbackCommand(
                            messageId,
                            request == null ? null : request.runId(),
                            request == null ? null : request.rating(),
                            request == null ? null : request.reasonCode(),
                            request == null ? null : request.commentText(),
                            request == null ? null : request.metadata()
                    ));
                    return viewAssembler.toDto(feedback);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping(value = "/messages/{messageId}/feedback")
    public Mono<MessageFeedbackDto> cancelFeedback(
            @PathVariable("messageId") String messageId,
            @RequestParam(value = "runId", required = false) String runId) {
        UserContext user = requestContextResolver.resolveChatUser();
        return Mono.fromCallable(() -> viewAssembler.toDto(feedbackService.cancel(user, messageId, runId)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
