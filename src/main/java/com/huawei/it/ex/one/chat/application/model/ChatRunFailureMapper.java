package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeSessionUnavailable;
import com.huawei.it.ex.one.intent.application.model.IntentRoutingFailedException;
import com.huawei.it.ex.one.chat.domain.ErrorEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps existing execution failures to the stable run.failed contract. */
public final class ChatRunFailureMapper {
    public ErrorEvent toEvent(String runId, String sessionId, Throwable failure) {
        IntentRoutingFailedException intentFailure = findCause(failure, IntentRoutingFailedException.class);
        if (intentFailure != null) {
            return intentFailureEvent(runId, sessionId);
        }
        if (runtimeSessionUnavailable(failure)) {
            String message = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "Runtime session 不存在或已损坏"
                    : failure.getMessage();
            return ErrorEvent.of(runId, sessionId, "RUNTIME_SESSION_UNAVAILABLE", message);
        }
        String code = relayWebSocketConfigTimeout(failure)
                ? "RELAY_WS_CONFIG_TIMEOUT"
                : isTimeout(failure) ? "RUNTIME_STREAM_TIMEOUT" : "RUN_ERROR";
        String message = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Runtime execution failed"
                : failure.getMessage();
        return ErrorEvent.of(runId, sessionId, code, message);
    }

    private ErrorEvent intentFailureEvent(String runId, String sessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", IntentRoutingFailedException.CODE);
        payload.put("message", IntentRoutingFailedException.USER_MESSAGE);
        payload.put("source", "intent-agent");
        payload.put("failureStrategy", "FAIL_RUN");
        payload.put("suggestedAction", "SELECT_DOMAIN_AGENT");
        payload.put("retryable", true);
        return ErrorEvent.of(runId, sessionId, IntentRoutingFailedException.CODE,
                IntentRoutingFailedException.USER_MESSAGE, Map.copyOf(payload));
    }

    public boolean runtimeSessionUnavailable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AgentRuntimeSessionUnavailable) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public boolean runtimeSessionUnavailable(Map<String, Object> payload) {
        return payload != null
                && "RUNTIME_SESSION_UNAVAILABLE".equals(String.valueOf(payload.get("code")));
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean relayWebSocketConfigTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("RELAY_WS_CONFIG_TIMEOUT")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("TimeoutException")
                    || (message != null && message.contains("Did not observe any item or terminal signal within"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
