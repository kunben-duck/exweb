package com.huawei.it.ex.one.infrastructure.persistence;

/** 会话最后一个run的轻量状态及metadata行。 */
public class ChatSessionLastRunSummaryRow {
    private String sessionId;
    private String status;
    private String metadataJson;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}
