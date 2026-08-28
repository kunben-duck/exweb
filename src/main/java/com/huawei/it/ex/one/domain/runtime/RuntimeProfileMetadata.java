package com.huawei.it.ex.one.domain.runtime;

import com.huawei.it.ex.one.domain.routing.RuntimeProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Relay RuntimeProfile 的服务端私有 metadata 契约。
 *
 * <p>Binding 保存扁平快照用于会话恢复，ChatRun 使用私有嵌套字段供跨实例 stop 使用。</p>
 */
public final class RuntimeProfileMetadata {
    public static final String RUN_METADATA_KEY = "_relayRuntimeProfile";
    public static final String PROFILE_KEY = "runtimeProfile";
    public static final String APP_MODE_KEY = "relayAppMode";
    public static final String ROLE_NAME_KEY = "relayRoleName";
    public static final String RELAY_EXPERT_PINNED_KEY = "relayExpertPinned";

    private RuntimeProfileMetadata() {
    }

    public static Map<String, Object> bindingMetadata(RuntimeProfile profile,
                                                      String delegateAppMode,
                                                      String domainExpertAppMode,
                                                      String runtimeRoleName) {
        RuntimeProfile normalized = profile == null ? RuntimeProfile.DELEGATE : profile;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(PROFILE_KEY, normalized.name());
        if (normalized == RuntimeProfile.DOMAIN_EXPERT) {
            metadata.put(APP_MODE_KEY, required(domainExpertAppMode, "Domain expert Relay appMode"));
            metadata.put(ROLE_NAME_KEY, required(runtimeRoleName, "Domain expert Relay roleName"));
        } else {
            metadata.put(APP_MODE_KEY, required(delegateAppMode, "Delegate Relay appMode"));
        }
        return Map.copyOf(metadata);
    }

    public static Snapshot bindingSnapshot(Map<String, Object> metadata,
                                           String delegateAppMode,
                                           String domainExpertAppMode) {
        Map<String, Object> source = metadata == null ? Map.of() : metadata;
        RuntimeProfile profile = profile(source.get(PROFILE_KEY), true);
        if (profile == RuntimeProfile.DOMAIN_EXPERT) {
            return new Snapshot(
                    profile,
                    required(textOrDefault(source.get(APP_MODE_KEY), domainExpertAppMode),
                            "Domain expert Relay appMode"),
                    required(text(source.get(ROLE_NAME_KEY)),
                            "Domain expert Relay roleName"));
        }
        return new Snapshot(
                RuntimeProfile.DELEGATE,
                required(textOrDefault(source.get(APP_MODE_KEY), delegateAppMode), "Delegate Relay appMode"),
                null);
    }

    public static Snapshot requestSnapshot(Map<String, Object> metadata,
                                           String delegateAppMode,
                                           String domainExpertAppMode) {
        Map<String, Object> source = metadata == null ? Map.of() : metadata;
        Object nested = source.get(RUN_METADATA_KEY);
        if (nested instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return bindingSnapshot(values, delegateAppMode, domainExpertAppMode);
        }
        return bindingSnapshot(source, delegateAppMode, domainExpertAppMode);
    }

    public static Map<String, Object> runMetadataOverlay(Map<String, Object> bindingMetadata,
                                                         String delegateAppMode,
                                                         String domainExpertAppMode) {
        Snapshot snapshot = bindingSnapshot(
                bindingMetadata, delegateAppMode, domainExpertAppMode);
        return Map.of(RUN_METADATA_KEY, snapshot.toMetadata());
    }

    /**
     * 从新版本 Binding 中复制已经固化的档案，不为存量无档案 Binding 猜测部署配置。
     */
    public static Map<String, Object> runMetadataOverlayFromBinding(Map<String, Object> bindingMetadata) {
        Map<String, Object> source = bindingMetadata == null ? Map.of() : bindingMetadata;
        String profileValue = text(source.get(PROFILE_KEY));
        if (profileValue == null) {
            return Map.of();
        }
        RuntimeProfile profile = profile(profileValue, false);
        Snapshot snapshot = new Snapshot(
                profile,
                required(text(source.get(APP_MODE_KEY)), "Relay appMode"),
                profile == RuntimeProfile.DOMAIN_EXPERT
                        ? required(text(source.get(ROLE_NAME_KEY)), "Domain expert Relay roleName")
                        : null);
        return Map.of(RUN_METADATA_KEY, snapshot.toMetadata());
    }

    /**
     * 原样复制 Binding 档案供事务外 adapter 校验，避免损坏数据阻断本地 stop 状态提交。
     */
    public static Map<String, Object> copyBindingProfileAsRunMetadata(Map<String, Object> bindingMetadata) {
        Map<String, Object> source = bindingMetadata == null ? Map.of() : bindingMetadata;
        if (!source.containsKey(PROFILE_KEY)) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        copyIfPresent(source, copied, PROFILE_KEY);
        copyIfPresent(source, copied, APP_MODE_KEY);
        copyIfPresent(source, copied, ROLE_NAME_KEY);
        return copied.isEmpty() ? Map.of() : Map.of(RUN_METADATA_KEY, Map.copyOf(copied));
    }

    public static Map<String, Object> copyRunMetadata(Map<String, Object> runMetadata) {
        if (runMetadata == null || !(runMetadata.get(RUN_METADATA_KEY) instanceof Map<?, ?> profile)) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        profile.forEach((key, value) -> {
            if (key != null && value != null) {
                copied.put(String.valueOf(key), value);
            }
        });
        return copied.isEmpty() ? Map.of() : Map.of(RUN_METADATA_KEY, Map.copyOf(copied));
    }

    public static Map<String, Object> removePrivateRunMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return metadata == null ? Map.of() : Map.copyOf(metadata);
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(metadata);
        sanitized.remove(RUN_METADATA_KEY);
        sanitized.remove(PROFILE_KEY);
        sanitized.remove(APP_MODE_KEY);
        sanitized.remove(ROLE_NAME_KEY);
        sanitized.remove(RELAY_EXPERT_PINNED_KEY);
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    /** 判断Binding是否为前端显式选择并固定续接的Relay专家。 */
    public static boolean isPinnedDomainExpert(Map<String, Object> metadata) {
        Map<String, Object> source = metadata == null ? Map.of() : metadata;
        return Boolean.TRUE.equals(source.get(RELAY_EXPERT_PINNED_KEY))
                && RuntimeProfile.DOMAIN_EXPERT.name().equals(text(source.get(PROFILE_KEY)))
                && text(source.get(ROLE_NAME_KEY)) != null;
    }

    private static RuntimeProfile profile(Object value, boolean missingAsDelegate) {
        String text = text(value);
        if (text == null && missingAsDelegate) {
            return RuntimeProfile.DELEGATE;
        }
        try {
            return RuntimeProfile.valueOf(required(text, "Relay runtimeProfile"));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported Relay runtimeProfile: " + text, ex);
        }
    }

    private static String textOrDefault(Object value, String fallback) {
        String normalized = text(value);
        return normalized == null ? fallback : normalized;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String required(String value, String field) {
        String normalized = text(value);
        if (normalized == null) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return normalized;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    /** Relay调用档案的不可变快照。 */
    public record Snapshot(RuntimeProfile profile, String appMode, String roleName) {
        public Snapshot {
            profile = profile == null ? RuntimeProfile.DELEGATE : profile;
            appMode = required(appMode, "Relay appMode");
            if (profile == RuntimeProfile.DOMAIN_EXPERT) {
                roleName = required(roleName, "Domain expert Relay roleName");
            } else {
                roleName = null;
            }
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(PROFILE_KEY, profile.name());
            metadata.put(APP_MODE_KEY, appMode);
            if (roleName != null) {
                metadata.put(ROLE_NAME_KEY, roleName);
            }
            return Map.copyOf(metadata);
        }
    }
}
