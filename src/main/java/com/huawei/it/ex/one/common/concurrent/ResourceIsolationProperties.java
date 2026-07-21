package com.huawei.it.ex.one.common.concurrent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部资源并发隔离配置。
 *
 * <p>这些限制是当前 JVM 的 bulkhead，用于保护 Relay Runtime、DomainAgent 和对象存储等慢外部系统。
 * 集群总并发需要结合实例数、网关限流和下游容量共同规划。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.resource-isolation")
public class ResourceIsolationProperties {
    /** 当前 JVM 允许并发调用 AgentRuntime 的最大数量。 */
    private int agentRuntimeMaxConcurrent = 64;
    /** 当前 JVM 允许并发调用 DomainAgent 的最大数量。 */
    private int domainAgentMaxConcurrent = 64;
    /** 当前 JVM 允许并发执行对象存储上传/下载的最大数量。 */
    private int documentStorageMaxConcurrent = 32;

    public int getAgentRuntimeMaxConcurrent() {
        return agentRuntimeMaxConcurrent;
    }

    public void setAgentRuntimeMaxConcurrent(int agentRuntimeMaxConcurrent) {
        this.agentRuntimeMaxConcurrent = agentRuntimeMaxConcurrent;
    }

    public int getDomainAgentMaxConcurrent() {
        return domainAgentMaxConcurrent;
    }

    public void setDomainAgentMaxConcurrent(int domainAgentMaxConcurrent) {
        this.domainAgentMaxConcurrent = domainAgentMaxConcurrent;
    }

    public int getDocumentStorageMaxConcurrent() {
        return documentStorageMaxConcurrent;
    }

    public void setDocumentStorageMaxConcurrent(int documentStorageMaxConcurrent) {
        this.documentStorageMaxConcurrent = documentStorageMaxConcurrent;
    }

    public int normalizedAgentRuntimeMaxConcurrent() {
        return Math.max(1, agentRuntimeMaxConcurrent);
    }

    public int normalizedDomainAgentMaxConcurrent() {
        return Math.max(1, domainAgentMaxConcurrent);
    }

    public int normalizedDocumentStorageMaxConcurrent() {
        return Math.max(1, documentStorageMaxConcurrent);
    }
}
