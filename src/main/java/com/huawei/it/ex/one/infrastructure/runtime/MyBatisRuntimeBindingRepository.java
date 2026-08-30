/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime;

import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RuntimeBinding 的数据库仓储实现。
 */
@Repository
public class MyBatisRuntimeBindingRepository implements RuntimeBindingRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final RuntimeBindingMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisRuntimeBindingRepository(RuntimeBindingMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<RuntimeBinding> findById(String bindingId) {
        if (bindingId == null || bindingId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findById(bindingId)).map(this::toDomain);
    }

    @Override
    public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider,
                                               String leafMessageId) {
        return Optional.ofNullable(mapper.findActive(tenantId, userId, sessionId, provider, leafMessageId)).map(this::toDomain);
    }

    @Override
    public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider) {
        return findActive(tenantId, userId, sessionId, provider, null);
    }

    @Override
    public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId, String provider) {
        return mapper.findActiveBySession(tenantId, userId, sessionId, provider).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId) {
        return mapper.findActiveBySessionAnyProvider(tenantId, userId, sessionId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<RuntimeBinding> findResumableBySession(String tenantId, String userId, String sessionId,
                                                       String provider) {
        return mapper.findResumableBySession(tenantId, userId, sessionId, provider).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public RuntimeBinding save(RuntimeBinding binding) {
        RuntimeBindingRow row = toRow(binding);
        int updated = mapper.update(row);
        if (updated == 0) {
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException ex) {
                // 数据库兼容写法：不使用具体数据库专有 upsert，并发插入成功后退回更新。
                mapper.update(row);
            }
        }
        return binding;
    }

    @Override
    @Transactional(timeoutString =
            "${financeex.domain-agent.binding-compensation-transaction-timeout-seconds:2}")
    public boolean completeActiveDomainAgentForRun(
            RuntimeBinding binding,
            String expectedLastRunId,
            String leafMessageId,
            Instant expiresAt,
            Instant updatedAt) {
        if (binding == null || expectedLastRunId == null || expectedLastRunId.isBlank()
                || leafMessageId == null || leafMessageId.isBlank()) {
            return false;
        }
        RuntimeBinding desired = new RuntimeBinding(
                binding.id(), binding.tenantId(), binding.userId(), binding.chatSessionId(), binding.provider(),
                leafMessageId, binding.runtimeSessionId(), binding.status(), binding.lastRunId(), expiresAt,
                binding.createdAt(), updatedAt, binding.metadata());
        return mapper.completeActiveDomainAgentForRun(toRow(desired), expectedLastRunId) == 1;
    }

    @Override
    @Transactional(timeoutString =
            "${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}")
    public Optional<RuntimeBinding> resumeInteractionWithExecutionGuard(
            RuntimeBinding binding,
            String expectedLastRunId,
            RunExecutionClaim claim) {
        if (binding == null || expectedLastRunId == null || expectedLastRunId.isBlank() || claim == null) {
            return Optional.empty();
        }
        RuntimeBindingRow row = toRow(binding);
        Integer executionLocked = mapper.lockInteractionResumeExecution(row, claim);
        if (executionLocked == null || executionLocked != 1) {
            return Optional.empty();
        }
        return mapper.updateInteractionResume(row, expectedLastRunId) == 1
                ? Optional.of(binding)
                : Optional.empty();
    }

    @Override
    public boolean lockRunExecutionForBindingMutation(
            String tenantId,
            String userId,
            String sessionId,
            RunExecutionClaim claim) {
        if (claim == null) {
            return false;
        }
        Integer locked = mapper.lockRunExecutionForBindingMutation(
                tenantId, userId, sessionId, claim);
        return locked != null && locked == 1;
    }

    @Override
    @Transactional(timeoutString =
            "${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}")
    public boolean restoreInteractionResume(String bindingId, String continueRunId, String sourceRunId) {
        return mapper.restoreInteractionResume(bindingId, continueRunId, sourceRunId) == 1;
    }

    @Override
    @Transactional(timeoutString =
            "${financeex.domain-agent.binding-compensation-transaction-timeout-seconds:2}")
    public boolean restoreUnstartedForRun(RuntimeBinding previousBinding, String currentRunId) {
        if (previousBinding == null || currentRunId == null || currentRunId.isBlank()) {
            return false;
        }
        return mapper.restoreUnstartedForRun(toRow(previousBinding), currentRunId) == 1;
    }

    @Override
    public boolean restoreCancelledAfterAdmission(
            RuntimeBinding previousBinding,
            Instant expectedCancelledUpdatedAt) {
        if (previousBinding == null || expectedCancelledUpdatedAt == null) {
            return false;
        }
        return mapper.restoreCancelledAfterAdmission(
                toRow(previousBinding), expectedCancelledUpdatedAt) == 1;
    }

    @Override
    @Transactional(timeoutString =
            "${financeex.domain-agent.binding-compensation-transaction-timeout-seconds:2}")
    public boolean cancelActiveForRun(String bindingId, String runId) {
        return mapper.cancelActiveForRun(bindingId, runId) == 1;
    }

    @Override
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public boolean cancelActiveForInteraction(
            RuntimeBinding binding,
            String sourceRunId,
            String continueRunId) {
        return mapper.cancelActiveForInteraction(toRow(binding), sourceRunId, continueRunId) == 1;
    }

    private RuntimeBindingRow toRow(RuntimeBinding binding) {
        RuntimeBindingRow row = new RuntimeBindingRow();
        row.setId(binding.id());
        row.setTenantId(binding.tenantId());
        row.setUserId(binding.userId());
        row.setChatSessionId(binding.chatSessionId());
        row.setProvider(binding.provider());
        row.setLeafMessageId(binding.leafMessageId());
        row.setRuntimeSessionId(binding.runtimeSessionId());
        row.setStatus(binding.status().name());
        row.setLastRunId(binding.lastRunId());
        row.setExpiresAt(binding.expiresAt());
        row.setMetadataJson(toJson(binding.metadata()));
        row.setCreatedAt(binding.createdAt());
        row.setUpdatedAt(binding.updatedAt());
        return row;
    }

    private RuntimeBinding toDomain(RuntimeBindingRow row) {
        return new RuntimeBinding(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getChatSessionId(),
                row.getProvider(),
                row.getLeafMessageId(),
                row.getRuntimeSessionId(),
                RuntimeBindingStatus.valueOf(row.getStatus()),
                row.getLastRunId(),
                row.getExpiresAt(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt(),
                fromJson(row.getMetadataJson())
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("RuntimeBinding metadata 序列化失败", ex);
        }
    }

    private Map<String, Object> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }
}
