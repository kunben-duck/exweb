package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationQuery;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** 通过企业内部同步Client查询DomainAgent技能配置的默认防腐层实现。 */
public final class DefaultDomainAgentSkillConfigurationProvider
        implements DomainAgentSkillConfigurationProvider {
    private final DomainAgentSkillConfigurationClient client;
    private final DomainAgentSkillConfigurationProperties properties;
    private final Scheduler ioScheduler;

    public DefaultDomainAgentSkillConfigurationProvider(
            DomainAgentSkillConfigurationClient client,
            DomainAgentSkillConfigurationProperties properties,
            Scheduler ioScheduler) {
        this.client = Objects.requireNonNull(client, "client");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.ioScheduler = Objects.requireNonNull(ioScheduler, "ioScheduler");
    }

    @Override
    public Mono<DomainAgentSkillConfiguration> findBySkillId(
            DomainAgentSkillConfigurationQuery query) {
        if (query == null) {
            return Mono.error(protocolError("DomainAgent skill configuration query is null"));
        }
        if (query.skillId() == null || query.skillId().isBlank()) {
            return Mono.error(protocolError("DomainAgent skill configuration skillId is blank"));
        }
        Duration timeout = properties.normalizedTimeout();
        if (timeout == null) {
            return Mono.error(protocolError("DomainAgent skill configuration timeout is not configured"));
        }
        String skillId = query.skillId().trim();
        return Mono.fromCallable(() -> client.findBySkillIds(List.of(skillId)))
                .subscribeOn(ioScheduler)
                .timeout(timeout)
                .switchIfEmpty(Mono.error(protocolError(
                        "DomainAgent skill configuration response is empty")))
                .map(response -> mapResponse(skillId, response))
                .onErrorMap(this::translateFailure);
    }

    private DomainAgentSkillConfiguration mapResponse(
            String requestedSkillId,
            SkillConfigurationResponse response) {
        if (response == null) {
            throw protocolError("DomainAgent skill configuration response is empty");
        }
        if (normalize(response.status()).isEmpty()) {
            throw protocolError("DomainAgent skill configuration response has no status");
        }
        if (!"success".equalsIgnoreCase(normalize(response.status()))) {
            throw unavailable("DomainAgent skill configuration service returned a failed status", null);
        }
        DomainAgentSkillConfiguration matched = null;
        for (SkillConfigurationItem item : safeItems(response.data())) {
            if (item == null || !requestedSkillId.equals(normalize(item.skillId()))) {
                continue;
            }
            DomainAgentSkillConfiguration candidate = new DomainAgentSkillConfiguration(
                    requestedSkillId,
                    parseSaveSession(item.isSaveSession()));
            if (matched != null && !Objects.equals(matched.saveSession(), candidate.saveSession())) {
                throw protocolError("Conflicting DomainAgent skill configuration entries");
            }
            matched = candidate;
        }
        return matched == null
                ? DomainAgentSkillConfiguration.unconfigured(requestedSkillId)
                : matched;
    }

    private Boolean parseSaveSession(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        return switch (normalized) {
            case "N" -> Boolean.FALSE;
            case "Y" -> Boolean.TRUE;
            default -> throw protocolError("Invalid isSaveSession value in DomainAgent skill configuration");
        };
    }

    private Throwable translateFailure(Throwable failure) {
        Throwable cause = Exceptions.unwrap(failure);
        if (cause instanceof DomainAgentSkillConfigurationException) {
            return cause;
        }
        if (cause instanceof TimeoutException) {
            return new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.TIMEOUT,
                    "DomainAgent skill configuration request timed out",
                    cause);
        }
        return unavailable("DomainAgent skill configuration request failed", cause);
    }

    private DomainAgentSkillConfigurationException protocolError(String message) {
        return new DomainAgentSkillConfigurationException(
                DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                message);
    }

    private DomainAgentSkillConfigurationException unavailable(String message, Throwable cause) {
        return new DomainAgentSkillConfigurationException(
                DomainAgentSkillConfigurationException.Reason.UNAVAILABLE,
                message,
                cause);
    }

    private List<SkillConfigurationItem> safeItems(List<SkillConfigurationItem> items) {
        return items == null ? List.of() : items;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
