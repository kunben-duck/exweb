package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.huawei.it.ex.one.common.http.AgentRuntimeForwardCookieProperties;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeRequest;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.netty.http.client.HttpClient;

final class RelayWebSocketConnectionSupport {
    private final RelayAgentProperties properties;
    private final AgentRuntimeForwardCookieProperties forwardCookieProperties;

    RelayWebSocketConnectionSupport(RelayAgentProperties properties,
                                    AgentRuntimeForwardCookieProperties forwardCookieProperties) {
        this.properties = properties;
        this.forwardCookieProperties = forwardCookieProperties;
    }

    URI endpointUri(AgentRuntimeRequest request) {
        String clientId = request.runId() == null || request.runId().isBlank()
                ? UUID.randomUUID().toString()
                : request.runId();
        return endpointUri(clientId);
    }

    URI endpointUri(String clientId) {
        String configured = websocketProperties().getUrl();
        String base = requireText(configured, "financeex.agent-runtime.relay.websocket.url 不能为空");
        String uri = base.contains("{clientId}")
                ? base.replace("{clientId}", clientId)
                : appendPathSegment(base, clientId);
        return URI.create(uri);
    }

    String interruptClientId(String runId) {
        String prefix = runId == null || runId.isBlank() ? "run" : runId;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return prefix + "-interrupt-" + suffix;
    }

    HttpHeaders outboundHeaders(RuntimeForwardHeaders forwardHeaders) {
        HttpHeaders headers = new HttpHeaders();
        if (forwardCookieProperties.isEnabled()
                && forwardHeaders != null && forwardHeaders.hasCookie()) {
            headers.set(HttpHeaders.COOKIE, forwardHeaders.cookieHeader());
        }
        return headers;
    }

    void validateFrameSize(String frame, String runId) {
        int maxBytes = maxFrameBytes(websocketProperties().getMaxFrameBytes());
        int actualBytes = frame == null ? 0 : frame.getBytes(StandardCharsets.UTF_8).length;
        if (actualBytes > maxBytes) {
            throw new RelayRuntimeProtocolException("Relay WebSocket frame exceeds max size. runId="
                    + runId + ", maxBytes=" + maxBytes + ", actualBytes=" + actualBytes);
        }
    }

    RelayAgentProperties.WebSocket websocketProperties() {
        return properties.getRelay().getWebsocket();
    }

    Duration configHandshakeTimeout() {
        Duration timeout = websocketProperties().getConfigHandshakeTimeout();
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10)
                : timeout;
    }

    Duration interruptAckTimeout() {
        Duration timeout = websocketProperties().getInterruptAckTimeout();
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
    }

    static WebSocketClient webSocketClient(RelayAgentProperties properties) {
        RelayAgentProperties.WebSocket websocket = properties.getRelay().getWebsocket();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis(websocket.getConnectTimeout()));
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient(httpClient);
        client.setMaxFramePayloadLength(maxFrameBytes(websocket.getMaxFrameBytes()));
        return client;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private static String appendPathSegment(String base, String segment) {
        return base.endsWith("/") ? base + segment : base + "/" + segment;
    }

    private static int connectTimeoutMillis(Duration timeout) {
        Duration safeTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
        long millis = safeTimeout.toMillis();
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, millis);
    }

    private static int maxFrameBytes(DataSize size) {
        if (size == null || size.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "financeex.agent-runtime.relay.websocket.max-frame-bytes must be greater than 0");
        }
        if (size.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "financeex.agent-runtime.relay.websocket.max-frame-bytes must not exceed "
                            + Integer.MAX_VALUE + " bytes");
        }
        return (int) size.toBytes();
    }
}
