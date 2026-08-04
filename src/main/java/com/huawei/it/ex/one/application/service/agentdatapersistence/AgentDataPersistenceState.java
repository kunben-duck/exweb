package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个 run 内共享的 assistant 留存策略快照。
 *
 * <p>该对象只允许收紧策略，确保同一 run 从不保存技能切换到普通技能后不会重新落库真实回答。</p>
 */
public final class AgentDataPersistenceState {
    private final AtomicReference<AgentDataPersistencePolicy> policy =
            new AtomicReference<>(AgentDataPersistencePolicy.FULL);
    private final AtomicBoolean resolved = new AtomicBoolean();
    private final AtomicBoolean runtimeDispatchStarted = new AtomicBoolean();
    private final AtomicReference<String> placeholderContent;

    public AgentDataPersistenceState(String placeholderContent) {
        this.placeholderContent = new AtomicReference<>(normalizePlaceholder(placeholderContent));
    }

    public static AgentDataPersistenceState full() {
        return new AgentDataPersistenceState(null);
    }

    public static AgentDataPersistenceState fromRunMetadata(
            Map<String, Object> metadata,
            String defaultPlaceholderContent) {
        AgentDataPersistenceState state = new AgentDataPersistenceState(defaultPlaceholderContent);
        AgentDataPersistenceMetadata.RunPolicySnapshot snapshot =
                AgentDataPersistenceMetadata.readRunPolicy(metadata);
        if (snapshot == null) {
            return state;
        }
        state.policy.set(snapshot.policy());
        state.resolved.set(true);
        state.runtimeDispatchStarted.set(
                snapshot.policy() == AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER
                        && snapshot.runtimeDispatchStarted());
        if (snapshot.placeholderContent() != null && !snapshot.placeholderContent().isBlank()) {
            state.usePlaceholderContent(snapshot.placeholderContent());
        }
        return state;
    }

    /**
     * 为新的 Interaction continuation 继承来源 run 的策略，但不继承来源 run 的执行启动状态。
     */
    public static AgentDataPersistenceState inheritFromRunMetadata(
            Map<String, Object> metadata,
            String defaultPlaceholderContent) {
        AgentDataPersistenceState state = new AgentDataPersistenceState(defaultPlaceholderContent);
        if (metadata == null || !metadata.containsKey(AgentDataPersistenceMetadata.RUN_METADATA_KEY)) {
            return state;
        }
        AgentDataPersistenceMetadata.RunPolicySnapshot snapshot =
                AgentDataPersistenceMetadata.readRunPolicy(metadata);
        if (snapshot == null) {
            throw new IllegalStateException("来源 run 的 assistant 留存策略 metadata 非法");
        }
        state.policy.set(snapshot.policy());
        state.resolved.set(true);
        if (snapshot.placeholderContent() != null && !snapshot.placeholderContent().isBlank()) {
            state.usePlaceholderContent(snapshot.placeholderContent());
        }
        return state;
    }

    public AgentDataPersistenceState tighten(AgentDataPersistencePolicy candidate) {
        AgentDataPersistencePolicy normalized = candidate == null
                ? AgentDataPersistencePolicy.FULL
                : candidate;
        policy.updateAndGet(current -> current.tighten(normalized));
        resolved.set(true);
        return this;
    }

    public AgentDataPersistencePolicy policy() {
        return policy.get();
    }

    public boolean placeholderMode() {
        return policy() == AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER;
    }

    public boolean resolved() {
        return resolved.get();
    }

    public String placeholderContent() {
        return placeholderContent.get();
    }

    public boolean runtimeDispatchStarted() {
        return runtimeDispatchStarted.get();
    }

    public AgentDataPersistenceState markRuntimeDispatchStarted() {
        runtimeDispatchStarted.set(true);
        return this;
    }

    public AgentDataPersistenceState usePlaceholderContent(String content) {
        placeholderContent.set(normalizePlaceholder(content));
        return this;
    }

    public Map<String, Object> runMetadataOverlay() {
        return resolved()
                ? AgentDataPersistenceMetadata.runMetadata(
                        policy(), placeholderContent(), runtimeDispatchStarted())
                : Map.of();
    }

    public Map<String, Object> runtimeDispatchStartedMetadataOverlay() {
        return resolved()
                ? AgentDataPersistenceMetadata.runMetadata(policy(), placeholderContent(), true)
                : Map.of();
    }

    private static String normalizePlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return new AgentDataPersistenceProperties().normalizedPlaceholderContent();
        }
        return value.trim();
    }
}
