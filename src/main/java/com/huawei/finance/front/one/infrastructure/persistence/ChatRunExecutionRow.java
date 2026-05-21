package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_run_execution_t 的行模型。
 */
public class ChatRunExecutionRow {
    /** 执行控制面记录主键。 */
    private String id;
    /** 关联的业务 runId，对应 fin_ex_chat_run_t.id。 */
    private String runId;
    /** 租户标识，用于多租户隔离和恢复负载治理。 */
    private String tenantId;
    /** 用户标识，用于用户级隔离和排障。 */
    private String userId;
    /** run 所属聊天会话 ID。 */
    private String sessionId;
    /** 执行控制面状态。 */
    private String executionStatus;
    /** 当前拥有 run 执行权的应用实例 ID。 */
    private String ownerInstanceId;
    /** owner 实例最后一次心跳时间。 */
    private Instant heartbeatAt;
    /** owner 实例运行租约到期时间。 */
    private Instant leaseUntil;
    /** 写事件栅栏令牌，用于拒绝旧实例迟到事件。 */
    private Long fencingToken;
    /** 最近一次恢复使用的策略名。 */
    private String recoveryStrategy;
    /** 最近一次执行恢复动作的实例 ID。 */
    private String recoveredByInstanceId;
    /** stale run 恢复尝试次数。 */
    private Integer recoveryAttempts;
    /** RECOVERING 状态恢复租约到期时间。 */
    private Instant recoveryLeaseUntil;
    /** Runtime 可靠断点恢复 token；Runtime 不支持时为空。 */
    private String runtimeResumeToken;
    /** 执行控制面扩展元数据 JSON。 */
    private String metadataJson;
    /** 记录创建时间。 */
    private Instant createdAt;
    /** 记录最后更新时间。 */
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(String executionStatus) { this.executionStatus = executionStatus; }
    public String getOwnerInstanceId() { return ownerInstanceId; }
    public void setOwnerInstanceId(String ownerInstanceId) { this.ownerInstanceId = ownerInstanceId; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(Instant heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Long getFencingToken() { return fencingToken; }
    public void setFencingToken(Long fencingToken) { this.fencingToken = fencingToken; }
    public String getRecoveryStrategy() { return recoveryStrategy; }
    public void setRecoveryStrategy(String recoveryStrategy) { this.recoveryStrategy = recoveryStrategy; }
    public String getRecoveredByInstanceId() { return recoveredByInstanceId; }
    public void setRecoveredByInstanceId(String recoveredByInstanceId) { this.recoveredByInstanceId = recoveredByInstanceId; }
    public Integer getRecoveryAttempts() { return recoveryAttempts; }
    public void setRecoveryAttempts(Integer recoveryAttempts) { this.recoveryAttempts = recoveryAttempts; }
    public Instant getRecoveryLeaseUntil() { return recoveryLeaseUntil; }
    public void setRecoveryLeaseUntil(Instant recoveryLeaseUntil) { this.recoveryLeaseUntil = recoveryLeaseUntil; }
    public String getRuntimeResumeToken() { return runtimeResumeToken; }
    public void setRuntimeResumeToken(String runtimeResumeToken) { this.runtimeResumeToken = runtimeResumeToken; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
