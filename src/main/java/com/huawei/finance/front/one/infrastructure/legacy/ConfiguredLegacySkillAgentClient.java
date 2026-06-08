package com.huawei.finance.front.one.infrastructure.legacy;

import com.huawei.finance.front.one.application.config.LegacySkillProperties;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentClient;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillCancelRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 配置化老 Agent 指定技能 HTTP adapter。
 *
 * <p>该实现只处理历史 skillId 兼容路径。老接口的请求体和 eventStream 响应在 adapter 内部完成
 * 映射，对外只返回 ChatService 标准 ChatEvent。</p>
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
        return webClientBuilder.build()
                .post()
                .uri(fullUrl(properties.getChatPath()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(properties.getTimeout())
                .flatMapIterable(chunk -> responseNormalizer.normalize(request.runId(), request.sessionId(), chunk));
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
}
