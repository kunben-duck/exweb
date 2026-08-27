package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.RouteMemoryProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceCorrectionRepository;
import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.domain.auth.UserContext;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class IntentPreferenceCorrectionLoaderTest {
    @Test
    void loadsConfiguredLimitForTheEffectiveAccessName() {
        IntentPreferenceCorrectionRepository repository = mock(IntentPreferenceCorrectionRepository.class);
        IntentServiceHttpProperties intent = new IntentServiceHttpProperties();
        intent.setAccessName("configured-entry");
        intent.setUserPreferenceCorrectionsLimit(5);
        IntentUserPreferenceCorrection correction = new IntentUserPreferenceCorrection(
                "问题", "偏好", "原始", Instant.parse("2026-08-27T02:00:00Z"));
        when(repository.findRecent("tenant", "user", "configured-entry", 5))
                .thenReturn(List.of(correction));
        IntentPreferenceCorrectionLoader loader = new IntentPreferenceCorrectionLoader(
                repository, new DefaultIntentAccessNameResolver(intent), intent,
                new RouteMemoryProperties(), Runnable::run);

        List<IntentUserPreferenceCorrection> loaded = loader.loadBlocking(
                null, new UserContext("tenant", "user", "User"));

        assertThat(loaded).containsExactly(correction);
        verify(repository).findRecent("tenant", "user", "configured-entry", 5);
    }

    @Test
    void zeroLimitSkipsTheDatabaseAndFailuresRemainOpen() {
        IntentPreferenceCorrectionRepository repository = mock(IntentPreferenceCorrectionRepository.class);
        IntentServiceHttpProperties disabled = new IntentServiceHttpProperties();
        disabled.setAccessName("entry");
        disabled.setUserPreferenceCorrectionsLimit(0);
        IntentPreferenceCorrectionLoader disabledLoader = new IntentPreferenceCorrectionLoader(
                repository, new DefaultIntentAccessNameResolver(disabled), disabled,
                new RouteMemoryProperties(), Runnable::run);

        assertThat(disabledLoader.loadBlocking(
                null, new UserContext("tenant", "user", "User"))).isEmpty();
        verifyNoInteractions(repository);

        IntentServiceHttpProperties enabled = new IntentServiceHttpProperties();
        enabled.setAccessName("entry");
        RouteMemoryProperties isolation = new RouteMemoryProperties();
        isolation.getCircuitBreaker().setFailureThreshold(1);
        when(repository.findRecent("tenant", "user", "entry", 5))
                .thenThrow(new IllegalStateException("database down"));
        IntentPreferenceCorrectionLoader failingLoader = new IntentPreferenceCorrectionLoader(
                repository, new DefaultIntentAccessNameResolver(enabled), enabled,
                isolation, Runnable::run);

        assertThat(failingLoader.loadBlocking(
                null, new UserContext("tenant", "user", "User"))).isEmpty();
        assertThat(failingLoader.loadBlocking(
                null, new UserContext("tenant", "user", "User"))).isEmpty();
        verify(repository, times(1)).findRecent("tenant", "user", "entry", 5);
    }
}
