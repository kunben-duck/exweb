package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatInteractionType;
import com.huawei.it.ex.one.chat.domain.ChatRun;

/**
 * Interaction continuation 的内部消息持久化策略。
 */
public enum InteractionMessageStrategy {
    REUSE_ASSISTANT,
    NEW_TURN;

    public static final String METADATA_KEY = "interactionMessageStrategy";

    public static InteractionMessageStrategy forInteraction(ChatInteractionRequest interaction) {
        return interaction != null && interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION
                ? NEW_TURN
                : REUSE_ASSISTANT;
    }

    public static InteractionMessageStrategy fromRun(ChatRun run) {
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

    public static boolean newTurn(ChatInteractionRequest interaction) {
        return forInteraction(interaction) == NEW_TURN;
    }

    public static boolean newTurn(ChatRun run) {
        return fromRun(run) == NEW_TURN;
    }
}
