package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import org.junit.jupiter.api.Test;

class LocalChatRunExecutionRegistryTest {
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
