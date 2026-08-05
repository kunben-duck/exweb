package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.routing.DomainExpertAccessNameResolver;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 解析 AMBIGUOUS_ROUTE 的可信候选，并统一用户选择与自动选择规则。
 */
final class AmbiguousRouteSelectionResolver {
    private final DomainExpertAccessNameResolver domainExpertResolver;

    AmbiguousRouteSelectionResolver() {
        this("");
    }

    AmbiguousRouteSelectionResolver(String domainExpertAccessNamePrefix) {
        this.domainExpertResolver = new DomainExpertAccessNameResolver(domainExpertAccessNamePrefix);
    }

    List<Candidate> candidates(ChatInteractionRequest interaction) {
        return interaction == null ? List.of() : candidates(interaction.requestPayload());
    }

    List<Candidate> candidates(Map<String, Object> payload) {
        Object value = candidateValue(payload);
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        int index = 0;
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                candidates.add(candidate(map, index));
            }
            index++;
        }
        return List.copyOf(candidates);
    }

    Optional<Candidate> select(ChatInteractionRequest interaction, String skillId) {
        String normalized = AmbiguousRouteSupport.firstText(skillId);
        if (normalized == null) {
            return Optional.empty();
        }
        return candidates(interaction).stream()
                .filter(Candidate::routable)
                .filter(candidate -> normalized.equals(candidate.skillId()))
                .findFirst();
    }

    Optional<Candidate> autoSelect(ChatInteractionRequest interaction) {
        return autoSelect(candidates(interaction));
    }

    Optional<Candidate> autoSelect(Map<String, Object> payload) {
        return autoSelect(candidates(payload));
    }

    RouteSignalResult routeSignal(Candidate candidate, String routeSource) {
        if (candidate == null || !candidate.routable()) {
            throw new IllegalArgumentException("AMBIGUOUS_ROUTE 不存在可执行的候选技能");
        }
        String source = AmbiguousRouteSupport.firstText(routeSource);
        if (source == null) {
            throw new IllegalArgumentException("AMBIGUOUS_ROUTE routeSource 不能为空");
        }
        IntentDecision decision = candidate.intentDecision();
        DomainExpertAccessNameResolver.Resolution expert = domainExpertResolver.resolve(candidate.skillId());
        if (expert.malformedDomainExpert()) {
            throw new IllegalArgumentException("AMBIGUOUS_ROUTE 专家候选缺少 roleName");
        }
        RouteTarget route = expert.validDomainExpert()
                ? RouteTarget.agentRuntime(source, candidate.confidence(),
                        "ambiguous route domain expert candidate selected", RuntimeProfile.DOMAIN_EXPERT,
                        expert.roleName())
                : RouteTarget.domainAgent(
                        candidate.skillId(),
                        source,
                        candidate.confidence(),
                        "ambiguous route candidate selected");
        return RouteSignalResult.ofIntent(route, decision, 0L, 0.0);
    }

    private Optional<Candidate> autoSelect(List<Candidate> candidates) {
        Candidate selected = null;
        for (Candidate candidate : candidates) {
            if (!candidate.routable()) {
                continue;
            }
            if (selected == null || candidate.confidence() > selected.confidence()) {
                selected = candidate;
            }
        }
        return Optional.ofNullable(selected);
    }

    private Object candidateValue(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object direct = payload.get("candidateIntents");
        if (direct != null) {
            return direct;
        }
        Object clarification = payload.get("clarification");
        return clarification instanceof Map<?, ?> map ? map.get("candidateIntents") : null;
    }

    private Candidate candidate(Map<?, ?> source, int index) {
        Map<String, Object> raw = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                raw.put(String.valueOf(key), value);
            }
        });
        return new Candidate(
                AmbiguousRouteSupport.firstText(raw.get("intentId")),
                AmbiguousRouteSupport.firstText(raw.get("intentName")),
                confidence(raw.get("confidence")),
                AmbiguousRouteSupport.firstText(raw.get("skillId")),
                index,
                ChatPayloadMaps.immutableCopy(raw));
    }

    private double confidence(Object value) {
        double parsed;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else {
            try {
                parsed = value == null ? 0.0 : Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                parsed = 0.0;
            }
        }
        return Double.isFinite(parsed) ? parsed : 0.0;
    }

    record Candidate(
            String intentId,
            String intentName,
            double confidence,
            String skillId,
            int responseOrder,
            Map<String, Object> raw
    ) {
        Candidate {
            raw = raw == null ? Map.of() : ChatPayloadMaps.immutableCopy(raw);
        }

        boolean routable() {
            return skillId != null && !skillId.isBlank();
        }

        IntentDecision intentDecision() {
            Map<String, Object> slots = new LinkedHashMap<>();
            slots.put("routeAction", "ROUTE_SINGLE");
            if (intentId != null) {
                slots.put("intentId", intentId);
            }
            Object resourceInstruction = raw.get("resourceInstruction");
            if (resourceInstruction != null) {
                slots.put("resourceInstruction", resourceInstruction);
            }
            return new IntentDecision(
                    intentId == null ? skillId : intentId,
                    intentName == null ? "未知意图" : intentName,
                    TaskComplexity.SIMPLE,
                    confidence,
                    true,
                    skillId,
                    Map.copyOf(slots),
                    List.of(),
                    raw);
        }
    }
}
