package com.huawei.it.ex.one.application.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.IntentCandidateProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidate;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateProvider;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateQueryException;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.domain.auth.UserContext;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class IntentCandidateApplicationServiceTest {
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final IntentCandidateProvider provider = mock(IntentCandidateProvider.class);
    private final IntentCandidateApplicationService service =
            new IntentCandidateApplicationService(messages, provider, properties(8));
    private final UserContext user = new UserContext("tenant1", "user1", "User One");

    @Test
    void queriesCandidatesForOwnedUserMessage() {
        List<IntentCandidate> expected = List.of(
                new IntentCandidate("intent-1", "EX_skill-1", "skill-1", "技能一", 0.92));
        when(messages.findRoleByOwnerAndId("tenant1", "user1", "msg-user"))
                .thenReturn(Optional.of("user"));
        when(provider.findCandidates(user, "msg-user")).thenReturn(Mono.just(expected));

        assertThat(service.findCandidates(user, " msg-user ").block()).isEqualTo(expected);
    }

    @Test
    void rejectsAssistantMessageBeforeCallingProvider() {
        when(messages.findRoleByOwnerAndId("tenant1", "user1", "msg-assistant"))
                .thenReturn(Optional.of("assistant"));

        assertThatThrownBy(() -> service.findCandidates(user, "msg-assistant").block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user消息");
        verifyNoInteractions(provider);
    }

    @Test
    void hidesMissingOrForeignMessageAsAccessDenied() {
        when(messages.findRoleByOwnerAndId("tenant1", "user1", "msg-foreign"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findCandidates(user, "msg-foreign").block())
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(provider);
    }

    @Test
    void rejectsBlankOrOverlongMessageIdBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.findCandidates(user, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.findCandidates(user, "m".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能超过64");
        verifyNoInteractions(messages, provider);
    }

    @Test
    void rejectsExcessConcurrencyBeforeRepositoryLookupAndReleasesPermitOnCancel() throws Exception {
        IntentCandidateApplicationService limitedService =
                new IntentCandidateApplicationService(messages, provider, properties(1));
        CountDownLatch providerSubscribed = new CountDownLatch(1);
        when(messages.findRoleByOwnerAndId("tenant1", "user1", "msg-user"))
                .thenReturn(Optional.of("user"));
        when(provider.findCandidates(user, "msg-user"))
                .thenReturn(Mono.<List<IntentCandidate>>never()
                        .doOnSubscribe(ignored -> providerSubscribed.countDown()), Mono.just(List.of()));

        Disposable first = limitedService.findCandidates(user, "msg-user").subscribe();
        assertThat(providerSubscribed.await(2, TimeUnit.SECONDS)).isTrue();

        StepVerifier.create(limitedService.findCandidates(user, "msg-user"))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure).isInstanceOf(IntentCandidateQueryException.class);
                    assertThat(((IntentCandidateQueryException) failure).isBusy()).isTrue();
                })
                .verify();
        verify(messages, times(1)).findRoleByOwnerAndId("tenant1", "user1", "msg-user");

        first.dispose();
        StepVerifier.create(limitedService.findCandidates(user, "msg-user"))
                .expectNext(List.of())
                .verifyComplete();
    }

    private IntentCandidateProperties properties(int maxConcurrency) {
        IntentCandidateProperties properties = new IntentCandidateProperties();
        properties.setMaxConcurrency(maxConcurrency);
        if (properties.getAuthIoMaxSize() > maxConcurrency) {
            properties.setAuthIoMaxSize(maxConcurrency);
        }
        return properties;
    }

}
