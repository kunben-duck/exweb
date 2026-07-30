package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatRun;

/**
 * Interaction continuation 的内部消息持久化策略。
 */
enum InteractionMessageStrategy {
    REUSE_ASSISTANT,
    NEW_TURN;

    static final String METADATA_KEY = "interactionMessageStrategy";

    static InteractionMessageStrategy forInteraction(ChatInteractionRequest interaction) {
        if (interaction == null || interaction.interactionType() != ChatInteractionType.INTENT_CLARIFICATION) {
            return REUSE_ASSISTANT;
        }
        return AmbiguousRouteSupport.isAmbiguous(interaction)
                ? REUSE_ASSISTANT
                : NEW_TURN;
    }

    static InteractionMessageStrategy fromRun(ChatRun run) {
        if (run == null || run.metadata() == null) {
            return REUSE_ASSISTANT;
        }
        Object value = run.metadata().get(METADATA_KEY);
        if (value == null) {
            return REUSE_ASSISTANT;
        }
        try {
            return valueOf(String.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return REUSE_ASSISTANT;
        }
    }

    static boolean newTurn(ChatInteractionRequest interaction) {
        return forInteraction(interaction) == NEW_TURN;
    }

    static boolean newTurn(ChatRun run) {
        return fromRun(run) == NEW_TURN;
    }
}
