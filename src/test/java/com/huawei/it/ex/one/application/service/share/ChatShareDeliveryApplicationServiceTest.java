/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.ChatShareDeliveryProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.share.ChatShareDeliveryProvider;
import com.huawei.it.ex.one.application.integration.share.ChatShareDeliveryRepository;
import com.huawei.it.ex.one.application.integration.share.ChatShareProviderDeliveryRequest;
import com.huawei.it.ex.one.application.integration.share.ChatShareProviderDeliveryResult;
import com.huawei.it.ex.one.application.integration.share.ChatShareRepository;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.ChatShareDelivery;
import com.huawei.it.ex.one.domain.chat.ChatShareMessageSnapshot;
import com.huawei.it.ex.one.domain.chat.ChatSharePage;
import com.huawei.it.ex.one.domain.chat.ChatShareSelectedMessageSnapshot;
import com.huawei.it.ex.one.domain.chat.ChatShareSnapshot;
import com.huawei.it.ex.one.domain.chat.ChatShareSummary;
import com.huawei.it.ex.one.domain.chat.ChatShareUnavailableException;
import com.huawei.it.ex.one.infrastructure.share.DefaultChatShareAccessPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

class ChatShareDeliveryApplicationServiceTest {
    private static final String WEB_PREFIX = "https://ai.finance.huawei.com/eureka/aiassistant/#/snapshot?shareId=";
    private static final String MOBILE_PREFIX = "h5://S013536/html/index.html#/share?shareId=";

    @Test
    void mobileShareLinkMatchesTheWireExampleExactly() {
        Fixture fixture = fixture(providerResult(true));
        String shareId = "share_6b2760e8d7164543876d7dcd37da2d2d";
        fixture.shares.save(selectedShare(shareId, List.of(selectedMessage("msg1", "user", "问题"))));
        fixture.properties.setShareUrlPrefix(" " + WEB_PREFIX + " \t");
        fixture.properties.setMobileShareUrlPrefix("\t " + MOBILE_PREFIX + " ");

        ChatShareDelivery delivery = deliver(fixture, shareId, " WELINK ");

        String encoded = "aDU6Ly9TMDEzNTM2L2h0bWwvaW5kZXguaHRtbCMvc2hhcmU/c2hhcmVJZD1z"
                + "aGFyZV82YjI3NjBlOGQ3MTY0NTQzODc2ZDdkY2QzN2RhMmQyZA==";
        assertThat(delivery.linkUrl()).isEqualTo(WEB_PREFIX + shareId + "&welink_open_uri=" + encoded);
        assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo(MOBILE_PREFIX + shareId);
        assertThat(((CapturingProvider) fixture.provider).lastRequest.linkUrl()).isEqualTo(delivery.linkUrl());
        assertThat(fixture.deliveries.saved).containsExactly(delivery);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n "})
    void absentMobilePrefixKeepsTheOriginalLink(String prefix) {
        Fixture fixture = fixture(providerResult(true));
        fixture.properties.setMobileShareUrlPrefix(prefix);

        assertThat(deliver(fixture, "share1", "welink").linkUrl())
                .isEqualTo("https://finex.example.com/share/share1");
    }

    @Test
    void usesUtf8AndStandardBase64WithoutUrlEncodingOrLineBreaks() {
        Fixture fixture = fixture(providerResult(true));
        String prefix = "h5://S013536/中文/😀/index1.html#/share?shareId=";
        fixture.properties.setMobileShareUrlPrefix(prefix);

        String link = deliver(fixture, "share1", "welink").linkUrl();
        String encoded = link.substring(link.indexOf("&welink_open_uri=") + "&welink_open_uri=".length());

        assertThat(encoded).isEqualTo(Base64.getEncoder().encodeToString((prefix + "share1")
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(encoded).contains("+", "/", "=").doesNotContain("%", "\r", "\n");
        assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo(prefix + "share1");
    }

    @Test
    void otherProvidersDoNotAppendMobileLinkOrApplyTheNewLengthCheck() {
        CapturingProvider provider = new CapturingProvider(providerResult(true)) {
            @Override
            public String providerCode() {
                return "custom";
            }
        };
        Fixture fixture = fixture(provider);
        fixture.properties.setMobileShareUrlPrefix(MOBILE_PREFIX);
        fixture.properties.setShareUrlPrefix("https://example.com/" + "a".repeat(1024));

        ChatShareDelivery delivery = deliver(fixture, "share1", "custom");

        assertThat(delivery.linkUrl()).isEqualTo(fixture.properties.getShareUrlPrefix() + "share1");
        assertThat(provider.lastRequest.linkUrl()).isEqualTo(delivery.linkUrl());
    }

    @ParameterizedTest
    @ValueSource(ints = {1023, 1024, 1025})
    void checksFinalLinkCodePointsBeforeDelivery(int length) {
        Fixture fixture = fixture(providerResult(true));
        fixture.properties.setMobileShareUrlPrefix(MOBILE_PREFIX);
        String encoded = Base64.getEncoder().encodeToString((MOBILE_PREFIX + "share1")
                .getBytes(StandardCharsets.UTF_8));
        String fixed = "https://example.com/#/snapshot?shareId=share1&welink_open_uri=" + encoded;
        fixture.properties.setShareUrlPrefix("https://example.com/#/"
                + "😀".repeat(length - fixed.length()) + "snapshot?shareId=");
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        if (length > 1024) {
            assertThatThrownBy(() -> deliver(fixture, "share1", "welink"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1024");
            assertThat(provider.lastRequest).isNull();
            assertThat(fixture.deliveries.saved).isEmpty();
        } else {
            String link = deliver(fixture, "share1", "welink").linkUrl();
            assertThat(link.codePointCount(0, link.length())).isEqualTo(length);
            assertThat(link.length()).isGreaterThan(1024);
            assertThat(provider.lastRequest.linkUrl()).isEqualTo(link);
        }
    }

    @Test
    void blankWebPrefixStillFailsBeforeSendingEvenWithMobileConfigured() {
        Fixture fixture = fixture(providerResult(true));
        fixture.properties.setShareUrlPrefix(" ");
        fixture.properties.setMobileShareUrlPrefix(MOBILE_PREFIX);

        assertThatThrownBy(() -> deliver(fixture, "share1", "welink"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("未配置 financeex.share.share-url-prefix");
        assertThat(((CapturingProvider) fixture.provider).lastRequest).isNull();
        assertThat(fixture.deliveries.saved).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mobileLinkDoesNotBypassRevocationOrExpiration(boolean revoked) {
        Fixture fixture = fixture(providerResult(true));
        fixture.properties.setMobileShareUrlPrefix(MOBILE_PREFIX);
        ChatShare original = share();
        ChatShare unavailable = revoked ? original.revoke(Instant.now()) : new ChatShare(
                original.id(), original.tenantId(), original.ownerUserId(), original.sourceSessionId(),
                original.sourceUserMessageId(), original.sourceAssistantMessageId(), original.sourceRunId(),
                original.title(), original.scope(), original.visibility(), original.status(), Instant.EPOCH,
                null, original.snapshot(), original.createdAt(), original.updatedAt());
        fixture.shares.save(unavailable);

        assertThatThrownBy(() -> deliver(fixture, "share1", "welink"))
                .isInstanceOf(ChatShareUnavailableException.class)
                .hasMessage(revoked ? "分享已撤销" : "分享已过期");
        assertThat(((CapturingProvider) fixture.provider).lastRequest).isNull();
        assertThat(fixture.deliveries.saved).isEmpty();
    }

    @Test
    void providerFailureStillSavesTheMobileLinkAndKeepsTheSnapshot() {
        Fixture fixture = fixture(providerResult(false));
        fixture.properties.setMobileShareUrlPrefix(MOBILE_PREFIX);
        ChatShare original = fixture.shares.findById("share1").orElseThrow();

        ChatShareDelivery delivery = deliver(fixture, "share1", "welink");

        assertThat(delivery.status()).isEqualTo("FAILED");
        assertThat(delivery.errorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(delivery.linkUrl()).contains("&welink_open_uri=");
        assertThat(((CapturingProvider) fixture.provider).lastRequest.linkUrl()).isEqualTo(delivery.linkUrl());
        assertThat(fixture.deliveries.saved).containsExactly(delivery);
        assertThat(fixture.shares.findById("share1")).contains(original);
    }

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
        assertThat(delivery.content()).isEmpty();
        assertThat(provider.lastRequest.content()).isEmpty();
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
    void contentIsConvertedToPlainTextBeforeSavingAndDelivery() {
        Fixture fixture = fixture(providerResult(true));

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null,
                " <p>经营&nbsp;分析：利润 / 成本 ± 5% 😀</p><!--hidden-->"
                        + "<script>alert('x')</script><style>.x{color:red}</style>"
                        + "<div>第二行 &lt; 10 &amp; &gt; 2</div> ",
                null, RuntimeForwardHeaders.empty()));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThat(delivery.content()).isEqualTo("经营 分析：利润 / 成本 ± 5% 😀 第二行 < 10 & > 2");
        assertThat(provider.lastRequest.content()).isEqualTo(delivery.content());
    }

    @Test
    void contentTruncationPreservesUnicodeCodePoint() {
        Fixture fixture = fixture(providerResult(true));
        fixture.properties.getDelivery().setContentMaxLength(3);

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null, "甲😀乙丙", null,
                RuntimeForwardHeaders.empty()));

        assertThat(delivery.content()).isEqualTo("甲😀乙");
    }

    @Test
    void contentAtRawInputLimitIsAccepted() {
        Fixture fixture = fixture(providerResult(true));

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null, "a".repeat(8192), null,
                RuntimeForwardHeaders.empty()));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThat(delivery.content()).isEqualTo("a".repeat(200));
        assertThat(provider.lastRequest.content()).isEqualTo(delivery.content());
    }

    @Test
    void oversizedRawContentIsRejectedBeforeProviderAndDeliveryPersistence() {
        Fixture fixture = fixture(providerResult(true));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThatThrownBy(() -> fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null, "a".repeat(8193), null,
                RuntimeForwardHeaders.empty())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("分享发送content长度不能超过8192");
        assertThat(provider.lastRequest).isNull();
        assertThat(fixture.deliveries.saved).isEmpty();
    }

    @Test
    void scriptAndStyleBlocksAreRemovedWithClosedOrUnclosedEnding() {
        Fixture fixture = fixture(providerResult(true));

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null,
                "前文<script>first()</script>中间<STYLE>body{color:red}</STYLE>后文<script>unfinished()",
                null, RuntimeForwardHeaders.empty()));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThat(delivery.content()).isEqualTo("前文 中间 后文");
        assertThat(provider.lastRequest.content()).isEqualTo(delivery.content());
    }

    @Test
    void repeatedUnclosedScriptInputAtLimitIsRemoved() {
        Fixture fixture = fixture(providerResult(true));

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null, "<script>".repeat(1024), null,
                RuntimeForwardHeaders.empty()));

        assertThat(delivery.content()).isEmpty();
    }

    @Test
    void htmlOnlyContentIsSentAsEmptyString() {
        Fixture fixture = fixture(providerResult(true));

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), null,
                "<p><!--hidden--><script>alert('x')</script><style>.x{}</style><br></p>",
                null, RuntimeForwardHeaders.empty()));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThat(delivery.content()).isEmpty();
        assertThat(provider.lastRequest.content()).isEmpty();
    }

    @Test
    void nullEmptyAndBlankContentAreSentAsEmptyStringWithoutSnapshotFallback() {
        for (String content : java.util.Arrays.asList(null, "", " \n\t ")) {
            Fixture fixture = fixture(providerResult(true));

            ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                    "share1", "welink", List.of("a"), List.of(), null, content, null,
                    RuntimeForwardHeaders.empty()));
            CapturingProvider provider = (CapturingProvider) fixture.provider;

            assertThat(delivery.content()).isEmpty();
            assertThat(provider.lastRequest.content()).isEmpty();
        }
    }

    @Test
    void safelyTruncatesDeliveryTitleToUtf8ColumnLimit() {
        Fixture fixture = fixture(providerResult(true));

        ChatShareDelivery delivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share1", "welink", List.of("a"), List.of(), "中".repeat(86), null,
                null, RuntimeForwardHeaders.empty()));
        CapturingProvider provider = (CapturingProvider) fixture.provider;

        assertThat(delivery.title()).isEqualTo("中".repeat(85));
        assertThat(delivery.title().getBytes(StandardCharsets.UTF_8)).hasSize(255);
        assertThat(provider.lastRequest.title()).isEqualTo(delivery.title());
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

    @Test
    void selectedMessagesDoNotProvideFallbackDeliveryContent() {
        Fixture fixture = fixture(providerResult(true));
        fixture.shares.save(selectedShare("share_selected", List.of(
                selectedMessage("msg_user", "user", "选中的问题"),
                selectedMessage("msg_assistant", "assistant", "选中的回答"))));
        fixture.shares.save(selectedShare("share_user_only", List.of(
                selectedMessage("msg_failed_user", "user", "失败轮次问题"))));

        ChatShareDelivery assistantDelivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share_selected", "welink", List.of("a"), List.of(), null, null, null,
                RuntimeForwardHeaders.empty()));
        ChatShareDelivery userDelivery = fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                "share_user_only", "welink", List.of("a"), List.of(), null, null, null,
                RuntimeForwardHeaders.empty()));

        assertThat(assistantDelivery.content()).isEmpty();
        assertThat(userDelivery.content()).isEmpty();
    }

    private ChatShareDelivery deliver(Fixture fixture, String shareId, String provider) {
        return fixture.service.deliver(user(), new CreateChatShareDeliveryCommand(
                shareId, provider, List.of("a"), List.of(), null, null, null, RuntimeForwardHeaders.empty()));
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

    private ChatShare selectedShare(String shareId, List<ChatShareSelectedMessageSnapshot> messages) {
        Instant now = Instant.now();
        String userMessageId = messages.stream()
                .filter(message -> "user".equals(message.role()))
                .map(ChatShareSelectedMessageSnapshot::messageId)
                .findFirst()
                .orElse(null);
        String assistantMessageId = messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .map(ChatShareSelectedMessageSnapshot::messageId)
                .findFirst()
                .orElse(null);
        return new ChatShare(shareId, "tenant1", "user1", "session1", userMessageId,
                assistantMessageId, null, "多消息分享", "SELECTED_MESSAGES", "INTERNAL", "ACTIVE",
                null, null, new ChatShareSnapshot(null, null, List.of(), messages, now), now, now);
    }

    private ChatShareSelectedMessageSnapshot selectedMessage(String messageId, String role, String content) {
        return new ChatShareSelectedMessageSnapshot(messageId, "session1", null, 1L, role, content,
                "run1", null, List.of(), List.of(), Instant.now());
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
            List<ChatShareSummary> items = shares.values().stream()
                    .filter(share -> tenantId.equals(share.tenantId()))
                    .filter(share -> ownerUserId.equals(share.ownerUserId()))
                    .map(ChatShareSummary::from)
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
