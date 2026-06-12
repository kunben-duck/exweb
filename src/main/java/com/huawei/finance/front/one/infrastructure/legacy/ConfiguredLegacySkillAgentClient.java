package com.huawei.finance.front.one.infrastructure.legacy;

import com.huawei.finance.front.one.application.config.LegacySkillProperties;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentClient;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 配置化老 Agent 指定技能 HTTP adapter。
 *
 * <p>该实现只处理历史 skillId 兼容路径。老接口的请求体和 eventStream 响应在 adapter 内部完成
 * 映射，对外只返回 ChatService 标准 ChatEvent。入口 Cookie 只作为出站 HTTP header 透传，
 * 不进入老 Agent 请求体。</p>
 */
@Component
@EnableConfigurationProperties(LegacySkillProperties.class)
public class ConfiguredLegacySkillAgentClient implements LegacySkillAgentClient {
    private static final Logger log = LoggerFactory.getLogger(ConfiguredLegacySkillAgentClient.class);

    private final WebClient.Builder webClientBuilder;
    private final LegacySkillProperties properties;
    private final LegacySkillChatRequestMapper requestMapper;
    private final LegacySkillResponseNormalizer responseNormalizer;

    public ConfiguredLegacySkillAgentClient(WebClient.Builder webClientBuilder,
                                            LegacySkillProperties properties,
                                            LegacySkillChatRequestMapper requestMapper,
                                            LegacySkillResponseNormalizer responseNormalizer) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.responseNormalizer = responseNormalizer;
    }

    @Override
    public Flux<ChatEvent> query(LegacySkillAgentRequest request) {
        validate(request);
        Map<String, Object> body = requestMapper.toWireRequest(request);
        return Flux.defer(() -> {
            LegacySkillResponseNormalizer.LegacySkillStreamState streamState = responseNormalizer.newStreamState();
            return webClientBuilder.build()
                    .post()
                    .uri(fullUrl(properties.getChatPath()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                    .headers(headers -> applyForwardedCookie(headers, request.forwardHeaders()))
                    .bodyValue(body)
                    .retrieve()
                    /*
                     * 老 Agent 使用非标准的 "message: {...}" 私有 eventStream 帧。WebClient 在
                     * text/event-stream 下按标准 SSE 解码 String 时只认 data 行，可能吞掉 message 行。
                     * 因此这里读取原始 DataBuffer，再交给 LegacySkillResponseNormalizer 兼容 message/data/plain JSON。
                     */
                    .bodyToFlux(DataBuffer.class)
                    .map(this::readUtf8)
                    .timeout(properties.getTimeout())
                    .flatMapIterable(chunk -> responseNormalizer.normalize(
                            request.runId(), request.sessionId(), chunk, streamState));
        });
    }

    @Override
    public Mono<Void> cancel(LegacySkillCancelRequest request) {
        if (!properties.isEnabled() || properties.getStopPath() == null || properties.getStopPath().isBlank()) {
            return Mono.empty();
        }
        Map<String, Object> body = Map.of(
                "runId", request.runId(),
                "sessionId", request.sessionId(),
                "skillId", request.skillId() == null ? "" : request.skillId(),
                "reason", request.reason() == null ? "" : request.reason()
        );
        return webClientBuilder.build()
                .post()
                .uri(fullUrl(properties.getStopPath()))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyForwardedCookie(headers, request.forwardHeaders()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(properties.getTimeout())
                .onErrorResume(ex -> {
                    log.warn("Legacy skill cancel failed. runId={}, reason={}", request.runId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    private void validate(LegacySkillAgentRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("LEGACY_SKILL_DISABLED: 历史指定技能服务未启用");
        }
        if (!properties.skillAllowed(request.skillId())) {
            throw new IllegalArgumentException("非法或未授权 skillId: " + request.skillId());
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("LEGACY_SKILL_BASE_URL_MISSING: 历史指定技能服务地址未配置");
        }
    }

    private void applyForwardedCookie(HttpHeaders headers, RuntimeForwardHeaders forwardHeaders) {
        if (forwardHeaders == null || !forwardHeaders.hasCookie()) {
            return;
        }
        /*
         * Cookie 只作为老 Agent 出站 HTTP 请求头透传。老 Agent wire body 由
         * LegacySkillChatRequestMapper 生成，不包含 forwardHeaders，避免企业登录态落入请求体、
         * metadata、事件 payload 或日志。
         */
        headers.set(HttpHeaders.COOKIE, forwardHeaders.cookieHeader());
    }

    private String fullUrl(String path) {
        String baseUrl = properties.getBaseUrl().trim();
        String nextPath = path == null ? "" : path.trim();
        if (nextPath.startsWith("http://") || nextPath.startsWith("https://")) {
            return nextPath;
        }
        if (baseUrl.endsWith("/") && nextPath.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + nextPath;
        }
        if (!baseUrl.endsWith("/") && !nextPath.startsWith("/")) {
            return baseUrl + "/" + nextPath;
        }
        return baseUrl + nextPath;
    }

    private String readUtf8(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
