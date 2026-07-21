package com.huawei.it.ex.one.share.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_share_delivery_t 持久化行对象。
 */
public class ChatShareDeliveryRow {
    private String id;
    private String tenantId;
    private String ownerUserId;
    private String shareId;
    private String provider;
    private String status;
    private String targetAccountsJson;
    private String groupIdsJson;
    private String title;
    private String content;
    private String language;
    private String linkUrl;
    private String providerResponseJson;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant sentAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getShareId() { return shareId; }
    public void setShareId(String shareId) { this.shareId = shareId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTargetAccountsJson() { return targetAccountsJson; }
    public void setTargetAccountsJson(String targetAccountsJson) { this.targetAccountsJson = targetAccountsJson; }
    public String getGroupIdsJson() { return groupIdsJson; }
    public void setGroupIdsJson(String groupIdsJson) { this.groupIdsJson = groupIdsJson; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getLinkUrl() { return linkUrl; }
    public void setLinkUrl(String linkUrl) { this.linkUrl = linkUrl; }
    public String getProviderResponseJson() { return providerResponseJson; }
    public void setProviderResponseJson(String providerResponseJson) { this.providerResponseJson = providerResponseJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
