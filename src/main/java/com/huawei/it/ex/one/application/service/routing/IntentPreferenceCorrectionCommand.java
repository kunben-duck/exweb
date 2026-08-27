package com.huawei.it.ex.one.application.service.routing;

/** Request-independent command for recording one trusted Intent preference correction. */
public record IntentPreferenceCorrectionCommand(
        String selectionType,
        String sourceMessageId,
        SelectedIntent selectedIntent,
        String interactionId,
        String intentAccessName
) {
    public record SelectedIntent(String intentId, String intentName) {
    }
}
