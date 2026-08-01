package com.huawei.it.ex.one.application.integration.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeInteractionDispatchStateTest {
    @Test
    void dispatchedResponseWinsOverAnEarlierBindingRestoreResult() {
        RuntimeInteractionDispatchState state = RuntimeInteractionDispatchState.tracked();
        state.markBindingRestored();

        state.markResponseDispatched();

        assertThat(state.responseDispatched()).isTrue();
        assertThat(state.cancelInteractionAfterFailure()).isTrue();
    }

    @Test
    void untrackedInteractionIgnoresDispatchUpdates() {
        RuntimeInteractionDispatchState state = RuntimeInteractionDispatchState.untracked();

        state.markResponseDispatched();

        assertThat(state.trackedInteraction()).isFalse();
        assertThat(state.responseDispatched()).isFalse();
    }
}
