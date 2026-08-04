package com.huawei.it.ex.one.domain.routing;

/**
 * AgentRuntime 的内部调用档案。
 *
 * <p>该值只用于区分同一 Runtime provider 的协议模式，不属于公开路由协议。</p>
 */
public enum RuntimeProfile {
    /** Relay 普通委托模式。 */
    DELEGATE,
    /** Relay 单领域专家模式。 */
    DOMAIN_EXPERT;

    public static RuntimeProfile forIntentCandidate(String accessName, String domainExpertAccessName) {
        if (accessName != null && domainExpertAccessName != null
                && accessName.trim().equals(domainExpertAccessName.trim())) {
            return DOMAIN_EXPERT;
        }
        return DELEGATE;
    }
}
