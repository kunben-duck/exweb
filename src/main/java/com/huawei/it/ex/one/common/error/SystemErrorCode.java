package com.huawei.it.ex.one.common.error;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stable system error vocabulary shared by every ChatService layer.
 */
public enum SystemErrorCode {
    UNKNOWN_SYSTEM_ERROR("FN-EX-CHAT-SYS-SUP-000", "SUP", false),
    INTERNAL_EXECUTION_FAILED("FN-EX-CHAT-SYS-SUP-001", "SUP", false),
    CONFIGURATION_INVALID("FN-EX-CHAT-SYS-SUP-002", "SUP", false),
    SERIALIZATION_FAILED("FN-EX-CHAT-SYS-SUP-003", "SUP", false),
    DESERIALIZATION_FAILED("FN-EX-CHAT-SYS-SUP-004", "SUP", false),
    TASK_REJECTED("FN-EX-CHAT-SYS-SUP-005", "SUP", true),
    OPERATION_TIMEOUT("FN-EX-CHAT-SYS-SUP-006", "SUP", true),
    RESOURCE_EXHAUSTED("FN-EX-CHAT-SYS-SUP-007", "SUP", true),

    DATABASE_ERROR("FN-EX-CHAT-SYS-DBS-000", "DBS", false),
    DATABASE_UNAVAILABLE("FN-EX-CHAT-SYS-DBS-001", "DBS", true),
    DATABASE_CONNECTION_TIMEOUT("FN-EX-CHAT-SYS-DBS-002", "DBS", true),
    DATABASE_QUERY_TIMEOUT("FN-EX-CHAT-SYS-DBS-003", "DBS", true),
    DATABASE_READ_FAILED("FN-EX-CHAT-SYS-DBS-004", "DBS", true),
    DATABASE_WRITE_FAILED("FN-EX-CHAT-SYS-DBS-005", "DBS", true),
    DATABASE_TRANSACTION_FAILED("FN-EX-CHAT-SYS-DBS-006", "DBS", false),
    DATABASE_CONSTRAINT_VIOLATION("FN-EX-CHAT-SYS-DBS-007", "DBS", false),
    DATABASE_SCHEMA_MISMATCH("FN-EX-CHAT-SYS-DBS-008", "DBS", false),
    DATABASE_CONNECTION_POOL_EXHAUSTED("FN-EX-CHAT-SYS-DBS-009", "DBS", true),

    REDIS_ERROR("FN-EX-CHAT-SYS-RED-000", "RED", false),
    REDIS_UNAVAILABLE("FN-EX-CHAT-SYS-RED-001", "RED", true),
    REDIS_COMMAND_TIMEOUT("FN-EX-CHAT-SYS-RED-002", "RED", true),
    REDIS_READ_FAILED("FN-EX-CHAT-SYS-RED-003", "RED", true),
    REDIS_WRITE_FAILED("FN-EX-CHAT-SYS-RED-004", "RED", true),
    REDIS_SERIALIZATION_FAILED("FN-EX-CHAT-SYS-RED-005", "RED", false),
    REDIS_DESERIALIZATION_FAILED("FN-EX-CHAT-SYS-RED-006", "RED", false),
    REDIS_PUBLISH_FAILED("FN-EX-CHAT-SYS-RED-007", "RED", true),
    REDIS_SUBSCRIBE_FAILED("FN-EX-CHAT-SYS-RED-008", "RED", true),
    REDIS_LOCK_FAILED("FN-EX-CHAT-SYS-RED-009", "RED", true),
    REDIS_CACHE_SYNC_FAILED("FN-EX-CHAT-SYS-RED-010", "RED", true),

    WEBSOCKET_ERROR("FN-EX-CHAT-SYS-WS-000", "WS", false),
    WEBSOCKET_HANDSHAKE_FAILED("FN-EX-CHAT-SYS-WS-001", "WS", true),
    WEBSOCKET_MESSAGE_PARSE_FAILED("FN-EX-CHAT-SYS-WS-002", "WS", false),
    WEBSOCKET_SERIALIZATION_FAILED("FN-EX-CHAT-SYS-WS-003", "WS", false),
    WEBSOCKET_SEND_FAILED("FN-EX-CHAT-SYS-WS-004", "WS", true),
    WEBSOCKET_OUTBOUND_OVERFLOW("FN-EX-CHAT-SYS-WS-005", "WS", true),
    WEBSOCKET_EXECUTOR_REJECTED("FN-EX-CHAT-SYS-WS-006", "WS", true),
    WEBSOCKET_TRANSPORT_ERROR("FN-EX-CHAT-SYS-WS-007", "WS", true),
    WEBSOCKET_UNEXPECTED_CLOSED("FN-EX-CHAT-SYS-WS-008", "WS", true),
    WEBSOCKET_SEQUENCE_MISMATCH("FN-EX-CHAT-SYS-WS-009", "WS", true),
    WEBSOCKET_RECOVERY_FAILED("FN-EX-CHAT-SYS-WS-010", "WS", true),

    RELAY_ERROR("FN-EX-CHAT-SYS-RLY-000", "RLY", false),
    RELAY_UNAVAILABLE("FN-EX-CHAT-SYS-RLY-001", "RLY", true),
    RELAY_CONNECT_TIMEOUT("FN-EX-CHAT-SYS-RLY-002", "RLY", true),
    RELAY_CONFIG_TIMEOUT("FN-EX-CHAT-SYS-RLY-003", "RLY", true),
    RELAY_CONFIG_HANDSHAKE_FAILED("FN-EX-CHAT-SYS-RLY-004", "RLY", false),
    RELAY_PROTOCOL_INVALID("FN-EX-CHAT-SYS-RLY-005", "RLY", false),
    RELAY_RESPONSE_PARSE_FAILED("FN-EX-CHAT-SYS-RLY-006", "RLY", false),
    RELAY_SESSION_UNAVAILABLE("FN-EX-CHAT-SYS-RLY-007", "RLY", false),
    RELAY_HEARTBEAT_TIMEOUT("FN-EX-CHAT-SYS-RLY-008", "RLY", true),
    RELAY_RUN_TIMEOUT("FN-EX-CHAT-SYS-RLY-009", "RLY", true),
    RELAY_INTERRUPT_FAILED("FN-EX-CHAT-SYS-RLY-010", "RLY", true),
    RELAY_OUTBOUND_FAILED("FN-EX-CHAT-SYS-RLY-011", "RLY", true),
    RELAY_UNEXPECTED_CLOSED("FN-EX-CHAT-SYS-RLY-012", "RLY", true),

    SHARE_ERROR("FN-EX-CHAT-SYS-SHR-000", "SHR", false),
    SHARE_CONFIGURATION_INVALID("FN-EX-CHAT-SYS-SHR-001", "SHR", false),
    SHARE_PROVIDER_NOT_FOUND("FN-EX-CHAT-SYS-SHR-002", "SHR", false),
    SHARE_DELIVERY_FAILED("FN-EX-CHAT-SYS-SHR-003", "SHR", true),
    SHARE_PAYLOAD_SERIALIZATION_FAILED("FN-EX-CHAT-SYS-SHR-004", "SHR", false),
    SHARE_EXECUTOR_REJECTED("FN-EX-CHAT-SYS-SHR-005", "SHR", true),

    WELINK_ERROR("FN-EX-CHAT-SYS-WLK-000", "WLK", false),
    WELINK_UNAVAILABLE("FN-EX-CHAT-SYS-WLK-001", "WLK", true),
    WELINK_TIMEOUT("FN-EX-CHAT-SYS-WLK-002", "WLK", true),
    WELINK_HTTP_CLIENT_ERROR("FN-EX-CHAT-SYS-WLK-003", "WLK", false),
    WELINK_HTTP_SERVER_ERROR("FN-EX-CHAT-SYS-WLK-004", "WLK", true),
    WELINK_EMPTY_RESPONSE("FN-EX-CHAT-SYS-WLK-005", "WLK", true),
    WELINK_RESPONSE_INVALID("FN-EX-CHAT-SYS-WLK-006", "WLK", false),
    WELINK_STATUS_FAILED("FN-EX-CHAT-SYS-WLK-007", "WLK", false),
    WELINK_AUTH_FAILED("FN-EX-CHAT-SYS-WLK-008", "WLK", false),

    DOCUMENT_SERVICE_ERROR("FN-EX-CHAT-SYS-DOC-000", "DOC", false),
    DOCUMENT_UPLOAD_FAILED("FN-EX-CHAT-SYS-DOC-001", "DOC", true),
    DOCUMENT_DOWNLOAD_FAILED("FN-EX-CHAT-SYS-DOC-002", "DOC", true),
    DOCUMENT_CONTENT_READ_FAILED("FN-EX-CHAT-SYS-DOC-003", "DOC", true),
    DOCUMENT_CONTENT_WRITE_FAILED("FN-EX-CHAT-SYS-DOC-004", "DOC", true),
    DOCUMENT_METADATA_SERIALIZATION_FAILED("FN-EX-CHAT-SYS-DOC-005", "DOC", false),
    DOCUMENT_METADATA_DESERIALIZATION_FAILED("FN-EX-CHAT-SYS-DOC-006", "DOC", false),
    DOCUMENT_PROVIDER_ADAPTER_FAILED("FN-EX-CHAT-SYS-DOC-007", "DOC", false),
    DOCUMENT_URL_RESOLUTION_FAILED("FN-EX-CHAT-SYS-DOC-008", "DOC", false),
    DOCUMENT_STREAM_FAILED("FN-EX-CHAT-SYS-DOC-009", "DOC", true),

    API_STORE_ERROR("FN-EX-CHAT-SYS-APS-000", "APS", false),
    API_STORE_UNAVAILABLE("FN-EX-CHAT-SYS-APS-001", "APS", true),
    API_STORE_TIMEOUT("FN-EX-CHAT-SYS-APS-002", "APS", true),
    API_STORE_HTTP_CLIENT_ERROR("FN-EX-CHAT-SYS-APS-003", "APS", false),
    API_STORE_HTTP_SERVER_ERROR("FN-EX-CHAT-SYS-APS-004", "APS", true),
    API_STORE_EMPTY_RESPONSE("FN-EX-CHAT-SYS-APS-005", "APS", true),
    API_STORE_RESPONSE_INVALID("FN-EX-CHAT-SYS-APS-006", "APS", false),
    API_STORE_STATUS_FAILED("FN-EX-CHAT-SYS-APS-007", "APS", false),
    API_STORE_AUTH_FAILED("FN-EX-CHAT-SYS-APS-008", "APS", false),

    OBJECT_STORAGE_ERROR("FN-EX-CHAT-SYS-OBS-000", "OBS", false),
    OBJECT_STORAGE_UNAVAILABLE("FN-EX-CHAT-SYS-OBS-001", "OBS", true),
    OBJECT_STORAGE_WRITE_FAILED("FN-EX-CHAT-SYS-OBS-002", "OBS", true),
    OBJECT_STORAGE_READ_FAILED("FN-EX-CHAT-SYS-OBS-003", "OBS", true),
    OBJECT_STORAGE_DELETE_FAILED("FN-EX-CHAT-SYS-OBS-004", "OBS", true),
    OBJECT_STORAGE_CONFIGURATION_INVALID("FN-EX-CHAT-SYS-OBS-005", "OBS", false),
    OBJECT_STORAGE_PATH_RESOLUTION_FAILED("FN-EX-CHAT-SYS-OBS-006", "OBS", false),
    OBJECT_STORAGE_TIMEOUT("FN-EX-CHAT-SYS-OBS-007", "OBS", true),

    INTENT_DECISION_ERROR("FN-EX-CHAT-SYS-ITD-000", "ITD", false),
    INTENT_DECISION_UNAVAILABLE("FN-EX-CHAT-SYS-ITD-001", "ITD", true),
    INTENT_DECISION_TIMEOUT("FN-EX-CHAT-SYS-ITD-002", "ITD", true),
    INTENT_DECISION_RATE_LIMITED("FN-EX-CHAT-SYS-ITD-003", "ITD", true),
    INTENT_DECISION_HTTP_CLIENT_ERROR("FN-EX-CHAT-SYS-ITD-004", "ITD", false),
    INTENT_DECISION_HTTP_SERVER_ERROR("FN-EX-CHAT-SYS-ITD-005", "ITD", true),
    INTENT_DECISION_EMPTY_RESPONSE("FN-EX-CHAT-SYS-ITD-006", "ITD", true),
    INTENT_DECISION_PROTOCOL_INVALID("FN-EX-CHAT-SYS-ITD-007", "ITD", false),
    INTENT_DECISION_RESPONSE_PARSE_FAILED("FN-EX-CHAT-SYS-ITD-008", "ITD", false),
    INTENT_DECISION_STATUS_FAILED("FN-EX-CHAT-SYS-ITD-009", "ITD", false),
    INTENT_DECISION_STREAM_FAILED("FN-EX-CHAT-SYS-ITD-010", "ITD", true),

    DOMAIN_AGENT_ERROR("FN-EX-CHAT-SYS-DAG-000", "DAG", false),
    AGENT_OVERLOADED("FN-EX-CHAT-SYS-DAG-001", "DAG", true),
    DOMAIN_AGENT_TIMEOUT("FN-EX-CHAT-SYS-DAG-002", "DAG", true),
    DOMAIN_AGENT_RATE_LIMITED("FN-EX-CHAT-SYS-DAG-003", "DAG", true),
    DOMAIN_AGENT_EXECUTION_FAILED("FN-EX-CHAT-SYS-DAG-004", "DAG", true),
    PROTOCOL_INVALID("FN-EX-CHAT-SYS-DAG-005", "DAG", false),
    RESPONSE_PARSE_FAILED("FN-EX-CHAT-SYS-DAG-006", "DAG", false),
    DOMAIN_AGENT_UNAVAILABLE("FN-EX-CHAT-SYS-DAG-007", "DAG", true),
    DOMAIN_AGENT_HTTP_CLIENT_ERROR("FN-EX-CHAT-SYS-DAG-008", "DAG", false),
    DOMAIN_AGENT_HTTP_SERVER_ERROR("FN-EX-CHAT-SYS-DAG-009", "DAG", true),
    DOMAIN_AGENT_STREAM_FAILED("FN-EX-CHAT-SYS-DAG-010", "DAG", true),
    DOMAIN_AGENT_CANCEL_FAILED("FN-EX-CHAT-SYS-DAG-011", "DAG", true),
    DOMAIN_AGENT_UNEXPECTED_CLOSED("FN-EX-CHAT-SYS-DAG-012", "DAG", true),

    MQS_ERROR("FN-EX-CHAT-SYS-MQS-000", "MQS", false),
    MQS_UNAVAILABLE("FN-EX-CHAT-SYS-MQS-001", "MQS", true),
    MQS_TIMEOUT("FN-EX-CHAT-SYS-MQS-002", "MQS", true),
    MQS_RATE_LIMITED("FN-EX-CHAT-SYS-MQS-003", "MQS", true),
    MQS_RESPONSE_INVALID("FN-EX-CHAT-SYS-MQS-004", "MQS", false),

    LLM_ERROR("FN-EX-CHAT-SYS-LLM-000", "LLM", false),
    LLM_UNAVAILABLE("FN-EX-CHAT-SYS-LLM-001", "LLM", true),
    LLM_TIMEOUT("FN-EX-CHAT-SYS-LLM-002", "LLM", true),
    LLM_CONTEXT_EXCEEDED("FN-EX-CHAT-SYS-LLM-003", "LLM", false),

    MCP_ERROR("FN-EX-CHAT-SYS-MCP-000", "MCP", false),
    MCP_UNAVAILABLE("FN-EX-CHAT-SYS-MCP-001", "MCP", true),
    MCP_TIMEOUT("FN-EX-CHAT-SYS-MCP-002", "MCP", true),
    MCP_TOOL_FAILED("FN-EX-CHAT-SYS-MCP-003", "MCP", true),

    A2A_ERROR("FN-EX-CHAT-SYS-A2A-000", "A2A", false),
    A2A_UNAVAILABLE("FN-EX-CHAT-SYS-A2A-001", "A2A", true),
    A2A_TIMEOUT("FN-EX-CHAT-SYS-A2A-002", "A2A", true),
    A2A_RESPONSE_INVALID("FN-EX-CHAT-SYS-A2A-003", "A2A", false),

    EDM_ERROR("FN-EX-CHAT-SYS-EDM-000", "EDM", false),
    EDM_UNAVAILABLE("FN-EX-CHAT-SYS-EDM-001", "EDM", true),
    EDM_TIMEOUT("FN-EX-CHAT-SYS-EDM-002", "EDM", true),
    EDM_DOCUMENT_NOT_FOUND("FN-EX-CHAT-SYS-EDM-003", "EDM", false),
    EDM_RESPONSE_INVALID("FN-EX-CHAT-SYS-EDM-004", "EDM", false),

    LTM_ERROR("FN-EX-CHAT-SYS-LTM-000", "LTM", false),
    LTM_UNAVAILABLE("FN-EX-CHAT-SYS-LTM-001", "LTM", true);

    private static final Map<String, SystemErrorCode> BY_CODE = buildCodeIndex();
    private static final Map<String, SystemErrorCode> FALLBACK_BY_ORIGIN = buildFallbackIndex();
    private static final Set<String> REGISTERED_UPSTREAM_ORIGINS = Set.of(
            "DAG", "MQS", "MCP", "A2A", "LLM", "EDM", "LTM");

    private final String code;
    private final String origin;
    private final boolean defaultRetryable;

    SystemErrorCode(String code, String origin, boolean defaultRetryable) {
        if (!code.matches("^FN-EX-CHAT-SYS-[A-Z0-9]{2,4}-[0-9]{3}$")) {
            throw new IllegalArgumentException("Invalid system error code: " + code);
        }
        if (!code.contains("-" + origin + "-")) {
            throw new IllegalArgumentException("System error origin does not match code: " + code);
        }
        this.code = code;
        this.origin = origin;
        this.defaultRetryable = defaultRetryable;
    }

    public String code() {
        return code;
    }

    public String reasonCode() {
        return name();
    }

    public String origin() {
        return origin;
    }

    public boolean defaultRetryable() {
        return defaultRetryable;
    }

    public static Optional<SystemErrorCode> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code.trim()));
    }

    public static SystemErrorCode fallbackForOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return UNKNOWN_SYSTEM_ERROR;
        }
        return FALLBACK_BY_ORIGIN.getOrDefault(origin.trim().toUpperCase(Locale.ROOT), UNKNOWN_SYSTEM_ERROR);
    }

    public static Optional<SystemErrorCode> fromRegisteredUpstreamCode(String code) {
        return fromCode(code).filter(value -> REGISTERED_UPSTREAM_ORIGINS.contains(value.origin()));
    }

    private static Map<String, SystemErrorCode> buildCodeIndex() {
        Map<String, SystemErrorCode> index = new LinkedHashMap<>();
        Arrays.stream(values()).forEach(value -> {
            SystemErrorCode previous = index.put(value.code(), value);
            if (previous != null) {
                throw new IllegalStateException("Duplicate system error code: " + value.code());
            }
        });
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, SystemErrorCode> buildFallbackIndex() {
        Map<String, SystemErrorCode> index = new LinkedHashMap<>();
        Arrays.stream(values())
                .filter(value -> value.code().endsWith("-000"))
                .forEach(value -> index.put(value.origin(), value));
        return Collections.unmodifiableMap(index);
    }
}
