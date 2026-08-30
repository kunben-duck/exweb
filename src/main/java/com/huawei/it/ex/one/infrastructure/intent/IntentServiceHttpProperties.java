/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.intent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 意图服务 HTTP adapter 配置。
 *
 * <p>该配置只描述下游 HTTP 边界，入参和出参字段映射由独立 mapper 承载，避免下游协议变化时
 * 反复修改 HTTP 调用编排。</p>
 */
@ConfigurationProperties(prefix = "financeex.intent")
@Validated
public class IntentServiceHttpProperties {
    private static final int MAX_NORMALIZED_RETRIES = 10;
    private static final String DEFAULT_NO_MATCH_AGENT_NAME = "FIN Supervisor Agent";

    /** 意图服务基础地址。 */
    private String baseUrl = "";
    /** 意图入口名称。 */
    private String accessName = "";
    /** 意图响应 items[].accessName 转换为内部路由标识时移除的可选前缀。 */
    private String responseAccessNamePrefix = "";
    /** 规范化 accessName 命中该前缀时进入 Relay 专家模式。 */
    private String domainExpertAccessNamePrefix = "";
    /** 规范化 accessName 精确命中该值时进入 Relay Delegate。 */
    private String sensitiveInformationAccessName = "";
    /** NO_MATCH 进入 Relay 时向用户展示的目标 Agent 名称。 */
    private String noMatchAgentName = DEFAULT_NO_MATCH_AGENT_NAME;
    /** 意图识别接口路径。 */
    private String recognizePath = "/intent-recognition-configuration/getIntentDecision";
    /** 意图识别流式接口路径。 */
    private String recognizeStreamPath = "/intent-recognition-configuration/getIntentDecisionStream";
    /** 按消息ID查询意图候选技能的接口路径。 */
    private String confidencePath = "/intent-recognition-configuration/getIntentConfidence";
    /** 意图识别调用协议，默认使用流式模式。 */
    private IntentInvocationMode invocationMode = IntentInvocationMode.STREAMING;
    /** 是否要求意图服务返回 trace。 */
    private boolean trace = false;
    /** 单次意图识别调用超时时间。 */
    private Duration timeout = Duration.ofSeconds(5);
    /** 流式调用等待首个业务事件的最长时间。 */
    private Duration streamFirstEventTimeout = Duration.ofSeconds(5);
    /** 流式调用相邻网络帧之间的最长空闲时间。 */
    private Duration streamIdleTimeout = Duration.ofSeconds(30);
    /** 单次流式调用尝试的最长总时间。 */
    private Duration streamTotalTimeout = Duration.ofSeconds(120);
    /** 流式调用获取企业鉴权请求头的最长时间。 */
    private Duration streamAuthTimeout = Duration.ofSeconds(5);
    /** 流式鉴权阻塞 IO 调度器最大线程数。 */
    private int streamAuthIoMaxSize = 4;
    /** 流式鉴权阻塞 IO 调度器队列容量。 */
    private int streamAuthIoQueueCapacity = 128;
    /** 意图服务调用失败后的最大重试次数；不包含首次调用，运行时会限制到安全上限。 */
    private int maxRetries = 3;
    /** 发送给意图服务的最近用户偏好纠正数量；0表示关闭数据库读取。 */
    @Min(0)
    @Max(20)
    private int userPreferenceCorrectionsLimit = 5;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAccessName() {
        return accessName;
    }

    public void setAccessName(String accessName) {
        this.accessName = accessName;
    }

    public String getResponseAccessNamePrefix() {
        return responseAccessNamePrefix;
    }

    public void setResponseAccessNamePrefix(String responseAccessNamePrefix) {
        this.responseAccessNamePrefix = responseAccessNamePrefix;
    }

    public String getDomainExpertAccessNamePrefix() {
        return domainExpertAccessNamePrefix;
    }

    public void setDomainExpertAccessNamePrefix(String domainExpertAccessNamePrefix) {
        this.domainExpertAccessNamePrefix = domainExpertAccessNamePrefix;
    }

    public String getSensitiveInformationAccessName() {
        return sensitiveInformationAccessName;
    }

    public void setSensitiveInformationAccessName(String sensitiveInformationAccessName) {
        this.sensitiveInformationAccessName = sensitiveInformationAccessName;
    }

    public String getNoMatchAgentName() {
        return noMatchAgentName;
    }

    public void setNoMatchAgentName(String noMatchAgentName) {
        this.noMatchAgentName = noMatchAgentName;
    }

    public String getRecognizePath() {
        return recognizePath;
    }

    public void setRecognizePath(String recognizePath) {
        this.recognizePath = recognizePath;
    }

    public String getRecognizeStreamPath() {
        return recognizeStreamPath;
    }

    public void setRecognizeStreamPath(String recognizeStreamPath) {
        this.recognizeStreamPath = recognizeStreamPath;
    }

    public String getConfidencePath() {
        return confidencePath;
    }

    public void setConfidencePath(String confidencePath) {
        this.confidencePath = confidencePath;
    }

    public IntentInvocationMode getInvocationMode() {
        return invocationMode;
    }

    public void setInvocationMode(IntentInvocationMode invocationMode) {
        this.invocationMode = invocationMode;
    }

    public boolean isTrace() {
        return trace;
    }

    public void setTrace(boolean trace) {
        this.trace = trace;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getStreamFirstEventTimeout() {
        return streamFirstEventTimeout;
    }

    public void setStreamFirstEventTimeout(Duration streamFirstEventTimeout) {
        this.streamFirstEventTimeout = streamFirstEventTimeout;
    }

    public Duration getStreamIdleTimeout() {
        return streamIdleTimeout;
    }

    public void setStreamIdleTimeout(Duration streamIdleTimeout) {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    public Duration getStreamTotalTimeout() {
        return streamTotalTimeout;
    }

    public void setStreamTotalTimeout(Duration streamTotalTimeout) {
        this.streamTotalTimeout = streamTotalTimeout;
    }

    public Duration getStreamAuthTimeout() {
        return streamAuthTimeout;
    }

    public void setStreamAuthTimeout(Duration streamAuthTimeout) {
        this.streamAuthTimeout = streamAuthTimeout;
    }

    public int getStreamAuthIoMaxSize() {
        return streamAuthIoMaxSize;
    }

    public void setStreamAuthIoMaxSize(int streamAuthIoMaxSize) {
        this.streamAuthIoMaxSize = streamAuthIoMaxSize;
    }

    public int getStreamAuthIoQueueCapacity() {
        return streamAuthIoQueueCapacity;
    }

    public void setStreamAuthIoQueueCapacity(int streamAuthIoQueueCapacity) {
        this.streamAuthIoQueueCapacity = streamAuthIoQueueCapacity;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getUserPreferenceCorrectionsLimit() {
        return userPreferenceCorrectionsLimit;
    }

    public void setUserPreferenceCorrectionsLimit(int userPreferenceCorrectionsLimit) {
        this.userPreferenceCorrectionsLimit = userPreferenceCorrectionsLimit;
    }

    public Duration normalizedTimeout() {
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
    }

    public int normalizedMaxRetries() {
        return Math.min(MAX_NORMALIZED_RETRIES, Math.max(0, maxRetries));
    }

    public Duration normalizedStreamFirstEventTimeout() {
        return positiveOrDefault(streamFirstEventTimeout, Duration.ofSeconds(5));
    }

    public Duration normalizedStreamIdleTimeout() {
        return positiveOrDefault(streamIdleTimeout, Duration.ofSeconds(30));
    }

    public Duration normalizedStreamTotalTimeout() {
        return positiveOrDefault(streamTotalTimeout, Duration.ofSeconds(120));
    }

    public Duration normalizedStreamAuthTimeout() {
        return positiveOrDefault(streamAuthTimeout, Duration.ofSeconds(5));
    }

    public int normalizedStreamAuthIoMaxSize() {
        return Math.max(1, streamAuthIoMaxSize);
    }

    public int normalizedStreamAuthIoQueueCapacity() {
        return Math.max(1, streamAuthIoQueueCapacity);
    }

    public String normalizedNoMatchAgentName() {
        String normalized = noMatchAgentName == null ? "" : noMatchAgentName.strip();
        return normalized.isEmpty() ? DEFAULT_NO_MATCH_AGENT_NAME : normalized;
    }

    private Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
