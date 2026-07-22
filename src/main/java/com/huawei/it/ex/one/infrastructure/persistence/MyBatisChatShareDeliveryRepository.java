package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.integration.share.ChatShareDeliveryRepository;
import com.huawei.it.ex.one.domain.chat.ChatShareDelivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Repository;

/**
 * 分享发送记录数据库仓储实现。
 */
@Repository
public class MyBatisChatShareDeliveryRepository implements ChatShareDeliveryRepository {
    private final ChatShareDeliveryMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatShareDeliveryRepository(ChatShareDeliveryMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatShareDelivery save(ChatShareDelivery delivery) {
        mapper.insert(toRow(delivery));
        return delivery;
    }

    private ChatShareDeliveryRow toRow(ChatShareDelivery delivery) {
        ChatShareDeliveryRow row = new ChatShareDeliveryRow();
        row.setId(delivery.id());
        row.setTenantId(delivery.tenantId());
        row.setOwnerUserId(delivery.ownerUserId());
        row.setShareId(delivery.shareId());
        row.setProvider(delivery.provider());
        row.setStatus(delivery.status());
        row.setTargetAccountsJson(toJson(delivery.targetAccounts()));
        row.setGroupIdsJson(toJson(delivery.groupIds()));
        row.setTitle(delivery.title());
        row.setContent(delivery.content());
        row.setLanguage(delivery.language());
        row.setLinkUrl(delivery.linkUrl());
        row.setProviderResponseJson(toJson(delivery.providerResponse()));
        row.setErrorCode(delivery.errorCode());
        row.setErrorMessage(delivery.errorMessage());
        row.setCreatedAt(delivery.createdAt());
        row.setSentAt(delivery.sentAt());
        row.setUpdatedAt(delivery.updatedAt());
        return row;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("ChatShareDelivery JSON 序列化失败", ex);
        }
    }
}
