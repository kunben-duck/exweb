package com.huawei.finance.front.one.infrastructure.task.mybatis;

import java.time.Instant;

/**
 * fin_ex_task_card_t 表行对象。
 *
 * <p>该对象只在 MyBatis 基础设施层使用，领域层统一使用 TaskCard record。</p>
 */
public class TaskCardRow {
    private String taskId;
    private String tenantId;
    private String userId;
    private String chatSessionId;
    private String bindingId;
    private String taskGoal;
    private String taskDomain;
    private String agentCode;
    private String agentSessionId;
    private String taskStatus;
    private String rawNormalizedStatus;
    private String requiredInputsJson;
    private String collectedSlotsJson;
    private String lastAgentMessage;
    private String confirmationQuestion;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String metadataJson;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getChatSessionId() { return chatSessionId; }
    public void setChatSessionId(String chatSessionId) { this.chatSessionId = chatSessionId; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getTaskGoal() { return taskGoal; }
    public void setTaskGoal(String taskGoal) { this.taskGoal = taskGoal; }
    public String getTaskDomain() { return taskDomain; }
    public void setTaskDomain(String taskDomain) { this.taskDomain = taskDomain; }
    public String getAgentCode() { return agentCode; }
    public void setAgentCode(String agentCode) { this.agentCode = agentCode; }
    public String getAgentSessionId() { return agentSessionId; }
    public void setAgentSessionId(String agentSessionId) { this.agentSessionId = agentSessionId; }
    public String getTaskStatus() { return taskStatus; }
    public void setTaskStatus(String taskStatus) { this.taskStatus = taskStatus; }
    public String getRawNormalizedStatus() { return rawNormalizedStatus; }
    public void setRawNormalizedStatus(String rawNormalizedStatus) { this.rawNormalizedStatus = rawNormalizedStatus; }
    public String getRequiredInputsJson() { return requiredInputsJson; }
    public void setRequiredInputsJson(String requiredInputsJson) { this.requiredInputsJson = requiredInputsJson; }
    public String getCollectedSlotsJson() { return collectedSlotsJson; }
    public void setCollectedSlotsJson(String collectedSlotsJson) { this.collectedSlotsJson = collectedSlotsJson; }
    public String getLastAgentMessage() { return lastAgentMessage; }
    public void setLastAgentMessage(String lastAgentMessage) { this.lastAgentMessage = lastAgentMessage; }
    public String getConfirmationQuestion() { return confirmationQuestion; }
    public void setConfirmationQuestion(String confirmationQuestion) { this.confirmationQuestion = confirmationQuestion; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
}
