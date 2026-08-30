/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class MyBatisChatInteractionRequestRepositoryTest {
    @Test
    void readsLegacyDomainAgentSwitchTypeAsRouteSwitchConfirmation() {
        ChatInteractionRequestRow row = waitingRow("DOMAIN_AGENT_SWITCH_CONFIRMATION");
        ChatInteractionRequestMapper mapper = mapper(row, new AtomicReference<>());
        MyBatisChatInteractionRequestRepository repository =
                new MyBatisChatInteractionRequestRepository(mapper, new ObjectMapper());

        ChatInteractionRequest request = repository
                .findByOwnerAndId("tenant1", "user1", "interaction1")
                .orElseThrow();

        assertThat(request.interactionType()).isEqualTo(ChatInteractionType.ROUTE_SWITCH_CONFIRMATION);
        assertThat(request.status()).isEqualTo(ChatInteractionStatus.WAITING);
    }

    @Test
    void writesOnlyCurrentRouteSwitchConfirmationType() {
        AtomicReference<ChatInteractionRequestRow> insertedRow = new AtomicReference<>();
        ChatInteractionRequestMapper mapper = mapper(null, insertedRow);
        MyBatisChatInteractionRequestRepository repository =
                new MyBatisChatInteractionRequestRepository(mapper, new ObjectMapper());
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        ChatInteractionRequest request = new ChatInteractionRequest(
                "interaction1", "tenant1", "user1", "session1", "run1", null,
                "user-message1", "assistant-message1", "domain-agent", "binding1", "runtime-session1", null,
                ChatInteractionType.ROUTE_SWITCH_CONFIRMATION, ChatInteractionStatus.WAITING,
                java.util.Map.of(), java.util.Map.of(), now.plusSeconds(60), null, null, now, now);

        repository.insert(request);

        assertThat(insertedRow.get()).isNotNull();
        assertThat(insertedRow.get().getInteractionType()).isEqualTo("ROUTE_SWITCH_CONFIRMATION");
    }

    private ChatInteractionRequestMapper mapper(ChatInteractionRequestRow foundRow,
                                                AtomicReference<ChatInteractionRequestRow> insertedRow) {
        return (ChatInteractionRequestMapper) Proxy.newProxyInstance(
                ChatInteractionRequestMapper.class.getClassLoader(),
                new Class<?>[] {ChatInteractionRequestMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByOwnerAndId" -> foundRow;
                    case "insert" -> {
                        insertedRow.set((ChatInteractionRequestRow) args[0]);
                        yield 1;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == int.class) {
            return 0;
        }
        if (List.class.isAssignableFrom(returnType)) {
            return List.of();
        }
        return null;
    }

    private ChatInteractionRequestRow waitingRow(String interactionType) {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        ChatInteractionRequestRow row = new ChatInteractionRequestRow();
        row.setId("interaction1");
        row.setTenantId("tenant1");
        row.setUserId("user1");
        row.setSessionId("session1");
        row.setSourceRunId("run1");
        row.setUserMessageId("user-message1");
        row.setAssistantMessageId("assistant-message1");
        row.setRuntimeProvider("domain-agent");
        row.setInteractionType(interactionType);
        row.setStatus("WAITING");
        row.setRequestPayloadJson("{}");
        row.setResponsePayloadJson("{}");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
