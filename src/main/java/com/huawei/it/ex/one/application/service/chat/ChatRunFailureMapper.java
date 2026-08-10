package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeSessionUnavailable;
import com.huawei.it.ex.one.application.service.routing.IntentRoutingFailedException;
import com.huawei.it.ex.one.application.service.runtime.RuntimeStreamLimitExceededException;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps execution failures to the existing stable run.failed contract. */
final class ChatRunFailureMapper {
    ErrorEvent toEvent(String runId, String sessionId, Throwable failure) {
        RuntimeStreamLimitExceededException streamLimit =
                findCause(failure, RuntimeStreamLimitExceededException.class);
        if (streamLimit != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", RuntimeStreamLimitExceededException.CODE);
            payload.put("limitType", streamLimit.limitType().name());
            payload.put("message", "Runtime流式输出超过服务内存保护上限，本轮已停止");
            return ErrorEvent.of(runId, sessionId, RuntimeStreamLimitExceededException.CODE,
                    "Runtime流式输出超过服务内存保护上限，本轮已停止", Map.copyOf(payload));
        }
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

    boolean runtimeSessionUnavailable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AgentRuntimeSessionUnavailable) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    boolean runtimeSessionUnavailable(Map<String, Object> payload) {
        return payload != null
                && "RUNTIME_SESSION_UNAVAILABLE".equals(String.valueOf(payload.get("code")));
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
