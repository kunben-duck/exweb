package com.huawei.finance.front.one.infrastructure.agent.agentscope;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "financeex.agent.agentscope")
public class AgentScopeProperties {
    private String baseUrl = "http://localhost:8000/v1";
    private String apiKey = "dummy-key";
    private String modelName = "finance-llm";
    private int maxIters = 8;
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public int getMaxIters() { return maxIters; }
    public void setMaxIters(int maxIters) { this.maxIters = maxIters; }
}
