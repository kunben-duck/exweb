package com.huawei.it.ex.one.share.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.share.application.config.ChatShareDeliveryProperties;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.share.application.client.ChatShareDeliveryProvider;
import com.huawei.it.ex.one.share.application.repository.ChatShareDeliveryRepository;
import com.huawei.it.ex.one.share.application.model.ChatShareProviderDeliveryRequest;
import com.huawei.it.ex.one.share.application.model.ChatShareProviderDeliveryResult;
import com.huawei.it.ex.one.share.application.repository.ChatShareRepository;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.share.domain.ChatShare;
import com.huawei.it.ex.one.share.domain.ChatShareDelivery;
import com.huawei.it.ex.one.share.domain.ChatShareMessageSnapshot;
import com.huawei.it.ex.one.share.domain.ChatSharePage;
import com.huawei.it.ex.one.share.domain.ChatShareSnapshot;
import com.huawei.it.ex.one.share.infrastructure.provider.DefaultChatShareAccessPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatShareDeliveryApplicationServiceTest {
    @Test
    void deliverMapsTargetsAndLinkToProviderRequest() {
        Fixture fixture = fixture(providerResult(true));

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a", "b", "a", " "), List.of("g1", "g2"),
                "发送标题", null, "zh_CN", RuntimeForwardHeaders.empty()));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThat(delivery.status()).isEqualTo("SUCCESS");
        assertThat(delivery.linkUrl()).isEqualTo("https://finex.example.com/share/share1");
        assertThat(provider.lastRequest.targetAccount()).isEqualTo("a,b");
        assertThat(provider.lastRequest.groupId()).isEqualTo("g1,g2");
        assertThat(provider.lastRequest.userAccount()).isEqualTo("user1");
        assertThat(provider.lastRequest.title()).isEqualTo("发送标题");
        assertThat(provider.lastRequest.content()).isEqualTo("这是一段很长的回答");
        assertThat(fixture.deliveries.saved).containsExactly(delivery);
    }

    @Test
    void providerFailureIsSavedAsFailedDelivery() {
        Fixture fixture = fixture(providerResult(false));

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null, null, null, RuntimeForwardHeaders.empty()));

        assertThat(delivery.status()).isEqualTo("FAILED");
        assertThat(delivery.errorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(fixture.shares.findById("share1")).isPresent();
    }

    @Test
    void deliverPassesForwardHeadersToProviderOnly() {
        Fixture fixture = fixture(providerResult(true));
        RuntimeForwardHeaders forwardHeaders = RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192);

        fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null, null, null, forwardHeaders));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThat(provider.lastRequest.forwardHeaders().cookieHeader()).isEqualTo("sid=abc");
    }

    @Test
    void contentIsTruncatedByConfiguration() {
        Fixture fixture = fixture(providerResult(true));
        fixture.properties.getDelivery().setContentMaxLength(5);

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null, "123456789", null, RuntimeForwardHeaders.empty()));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThat(delivery.content()).isEqualTo("12345");
        assertThat(provider.lastRequest.content()).isEqualTo("12345");
    }

    @Test
    void deliveryConcurrencyLimitReturnsFailedRecordWithoutBlockingCaller() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        Fixture fixture = fixture(provider, properties -> properties.getDelivery().setMaxConcurrency(1));
        CompletableFuture<ChatShareDelivery> first = CompletableFuture.supplyAsync(() -> fixture.service.deliver(user(),
                new CreateChatShareDeliveryCommand(
                        "share1", "welink", List.of("a"), List.of(), null, null, null, RuntimeForwardHeaders.empty())));
        assertThat(provider.entered.await(2, TimeUnit.SECONDS)).isTrue();

        ChatShareDelivery second = fixture.service.deliver(user(),
                new CreateChatShareDeliveryCommand(
                        "share1", "welink", List.of("b"), List.of(), null, null, null, RuntimeForwardHeaders.empty()));
        provider.release.countDown();

        assertThat(second.status()).isEqualTo("FAILED");
        assertThat(second.errorCode()).isEqualTo("SHARE_DELIVERY_BUSY");
        assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
    }

    @Test
    void defaultPolicyOnlyAllowsOwnerToDeliver() {
        Fixture fixture = fixture(providerResult(true));

        assertThatThrownBy(() -> fixture.service.deliver(new UserContext("tenant1", "user2", "User Two"),
                new CreateChatShareDeliveryCommand(
                        "share1", "welink", List.of("a"), List.of(), null, null, null, RuntimeForwardHeaders.empty())))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void targetAccountsOrGroupsAreRequired() {
        Fixture fixture = fixture(providerResult(true));

        assertThatThrownBy(() -> fixture.service.deliver(user(),
                new CreateChatShareDeliveryCommand(
                        "share1", "welink", List.of(" "), List.of(), null, null, null, RuntimeForwardHeaders.empty())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个");
    }

    private Fixture fixture(ChatShareProviderDeliveryResult providerResult) {
        return fixture(new CapturingProvider(providerResult));
    }

    private Fixture fixture(ChatShareDeliveryProvider provider) {
        return fixture(provider, properties -> { });
    }

    private Fixture fixture(ChatShareDeliveryProvider provider, Consumer<ChatShareDeliveryProperties> customizer) {
        InMemoryShareRepository shares = new InMemoryShareRepository();
        InMemoryDeliveryRepository deliveries = new InMemoryDeliveryRepository();
        ChatShareDeliveryProperties properties = new ChatShareDeliveryProperties();
        properties.setShareUrlPrefix("https://finex.example.com/share/");
        customizer.accept(properties);
        ChatShareDeliveryApplicationService service = new ChatShareDeliveryApplicationService(
                shares,
                deliveries,
                new ChatShareDeliveryProviderRegistry(List.of(provider)),
                new FixedIdGenerator(),
                new PermissionChecker(),
                new DefaultChatShareAccessPolicy(),
                properties
        );
        shares.save(share());
        return new Fixture(service, shares, deliveries, provider, properties);
    }

    private ChatShareProviderDeliveryResult providerResult(boolean success) {
        return success
                ? ChatShareProviderDeliveryResult.success(Map.of("status", "200"))
                : ChatShareProviderDeliveryResult.failed("PROVIDER_ERROR", "provider failed", Map.of("status", "500"));
    }

    private ChatShare share() {
        Instant now = Instant.now();
        ChatShareMessageSnapshot question = new ChatShareMessageSnapshot(
                "msg_user", "session1", "user", "问题", "run1", null, List.of(), now);
        ChatShareMessageSnapshot answer = new ChatShareMessageSnapshot(
                "msg_assistant", "session1", "assistant", "这是一段很长的回答", "run1", null, List.of(), now);
        return new ChatShare("share1", "tenant1", "user1", "session1", "msg_user",
                "msg_assistant", "run1", "分享标题", "SINGLE_TURN", "INTERNAL", "ACTIVE",
                null, null, new ChatShareSnapshot(question, answer, List.of(), now), now, now);
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private record Fixture(ChatShareDeliveryApplicationService service,
                           InMemoryShareRepository shares,
                           InMemoryDeliveryRepository deliveries,
                           ChatShareDeliveryProvider provider,
                           ChatShareDeliveryProperties properties) {}

    private static class CapturingProvider implements ChatShareDeliveryProvider {
        private final ChatShareProviderDeliveryResult result;
        private ChatShareProviderDeliveryRequest lastRequest;

        CapturingProvider(ChatShareProviderDeliveryResult result) {
            this.result = result;
        }

        @Override
        public String providerCode() {
            return "welink";
        }

        @Override
        public ChatShareProviderDeliveryResult deliver(ChatShareProviderDeliveryRequest request) {
            this.lastRequest = request;
            return result;
        }
    }

    private static final class BlockingProvider implements ChatShareDeliveryProvider {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String providerCode() {
            return "welink";
        }

        @Override
        public ChatShareProviderDeliveryResult deliver(ChatShareProviderDeliveryRequest request) {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ChatShareProviderDeliveryResult.success(Map.of("status", "200"));
        }
    }

    private static class InMemoryDeliveryRepository implements ChatShareDeliveryRepository {
        private final List<ChatShareDelivery> saved = new ArrayList<>();

        @Override
        public ChatShareDelivery save(ChatShareDelivery delivery) {
            saved.add(delivery);
            return delivery;
        }
    }

    private static class InMemoryShareRepository implements ChatShareRepository {
        private final Map<String, ChatShare> shares = new LinkedHashMap<>();

        @Override
        public ChatShare save(ChatShare share) {
            shares.put(share.id(), share);
            return share;
        }

        @Override
        public Optional<ChatShare> findById(String shareId) {
            return Optional.ofNullable(shares.get(shareId));
        }

        @Override
        public ChatSharePage pageByOwner(String tenantId, String ownerUserId, int curPage, int pageSize) {
            List<ChatShare> items = shares.values().stream()
                    .filter(share -> tenantId.equals(share.tenantId()))
                    .filter(share -> ownerUserId.equals(share.ownerUserId()))
                    .toList();
            return new ChatSharePage(items, curPage, pageSize, items.size(), items.isEmpty() ? 0 : 1);
        }

        @Override
        public void revokeActiveBySession(String tenantId, String ownerUserId, String sessionId, Instant revokedAt) {
        }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override
        public String newId(String bizType, IdGenerateContext context) {
            return bizType + "_1";
        }
    }
}
