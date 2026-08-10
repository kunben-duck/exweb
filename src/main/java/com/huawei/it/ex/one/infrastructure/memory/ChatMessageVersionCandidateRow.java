package com.huawei.it.ex.one.infrastructure.memory;

import java.time.Instant;

/** MyBatis 承接当前页消息版本摘要的轻量行模型。 */
public class ChatMessageVersionCandidateRow {
    private String pageMessageId;
    private String messageId;
    private String role;
    private Integer siblingIndex;
    private Boolean locked;
    private String originType;
    private String editedFromMessageId;
    private String regeneratedFromMessageId;
    private Instant createdAt;
    private String switchLeafMessageId;

    public String getPageMessageId() {
        return pageMessageId;
    }

    public void setPageMessageId(String pageMessageId) {
        this.pageMessageId = pageMessageId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getSiblingIndex() {
        return siblingIndex;
    }

    public void setSiblingIndex(Integer siblingIndex) {
        this.siblingIndex = siblingIndex;
    }

    public Boolean getLocked() {
        return locked;
    }

    public void setLocked(Boolean locked) {
        this.locked = locked;
    }

    public String getOriginType() {
        return originType;
    }

    public void setOriginType(String originType) {
        this.originType = originType;
    }

    public String getEditedFromMessageId() {
        return editedFromMessageId;
    }

    public void setEditedFromMessageId(String editedFromMessageId) {
        this.editedFromMessageId = editedFromMessageId;
    }

    public String getRegeneratedFromMessageId() {
        return regeneratedFromMessageId;
    }

    public void setRegeneratedFromMessageId(String regeneratedFromMessageId) {
        this.regeneratedFromMessageId = regeneratedFromMessageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getSwitchLeafMessageId() {
        return switchLeafMessageId;
    }

    public void setSwitchLeafMessageId(String switchLeafMessageId) {
        this.switchLeafMessageId = switchLeafMessageId;
    }
}
