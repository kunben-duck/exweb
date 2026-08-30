/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.Disposable;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

class LocalChatRunExecutionRegistryTest {
    @Test
    void conditionalCancelDisposesOnlyMatchingClaim() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim oldClaim = new RunExecutionClaim("run-1", "instance-a", 1L);
        RunExecutionClaim newClaim = new RunExecutionClaim("run-1", "instance-a", 2L);
        AtomicBoolean disposed = new AtomicBoolean();
        Disposable subscription = () -> disposed.set(true);
        registry.register("run-1", subscription, newClaim);

        assertThat(registry.cancel(oldClaim)).isFalse();
        assertThat(disposed).isFalse();
        assertThat(registry.activeClaims()).containsExactly(newClaim);

        assertThat(registry.cancel(newClaim)).isTrue();
        assertThat(disposed).isTrue();
        assertThat(registry.activeClaims()).isEmpty();
    }

    @Test
    void conditionalCancelKeepsClaimUntilSubscriptionIsRegistered() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 1L);
        registry.registerClaim(claim);

        assertThat(registry.cancel(claim)).isFalse();
        assertThat(registry.activeClaims()).containsExactly(claim);

        AtomicBoolean disposed = new AtomicBoolean();
        registry.register("run-1", () -> disposed.set(true));
        assertThat(registry.cancel(claim)).isTrue();
        assertThat(disposed).isTrue();
    }

    @Test
    void conditionalCompletionDoesNotRemoveNewerFencingOwner() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim oldClaim = new RunExecutionClaim("run-1", "instance-a", 1L);
        RunExecutionClaim newClaim = new RunExecutionClaim("run-1", "instance-b", 2L);
        registry.registerClaim(oldClaim);
        registry.registerClaim(newClaim);

        registry.complete(oldClaim);

        assertThat(registry.activeClaims()).containsExactly(newClaim);
        registry.complete(newClaim);
        assertThat(registry.activeClaims()).isEmpty();
    }
}
