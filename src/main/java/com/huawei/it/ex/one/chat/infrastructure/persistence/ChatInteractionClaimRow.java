package com.huawei.it.ex.one.chat.infrastructure.persistence;

import java.time.Instant;

/**
 * Interaction claim 更新参数。
 */
public class ChatInteractionClaimRow {
    private String tenantId;
    private String userId;
    private String interactionId;
    private String continueRunId;
    private String responsePayloadJson;
    private Instant now;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getInteractionId() { return interactionId; }
    public void setInteractionId(String interactionId) { this.interactionId = interactionId; }
    public String getContinueRunId() { return continueRunId; }
    public void setContinueRunId(String continueRunId) { this.continueRunId = continueRunId; }
    public String getResponsePayloadJson() { return responsePayloadJson; }
    public void setResponsePayloadJson(String responsePayloadJson) { this.responsePayloadJson = responsePayloadJson; }
    public Instant getNow() { return now; }
    public void setNow(Instant now) { this.now = now; }
}
