/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.huawei.it.ex.one.application.config.ChatShareDeliveryProperties;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.integration.share.ChatShareDeliveryProvider;
import com.huawei.it.ex.one.application.integration.share.ChatShareDeliveryRepository;
import com.huawei.it.ex.one.application.integration.share.ChatShareProviderDeliveryRequest;
import com.huawei.it.ex.one.application.integration.share.ChatShareProviderDeliveryResult;
import com.huawei.it.ex.one.application.integration.share.ChatShareRepository;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.application.service.share.ChatShareApplicationService;
import com.huawei.it.ex.one.application.service.share.ChatShareDeliveryApplicationService;
import com.huawei.it.ex.one.application.service.share.ChatShareDeliveryProviderRegistry;
import com.huawei.it.ex.one.application.service.share.SelectedChatShareApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.ChatShareDelivery;
import com.huawei.it.ex.one.infrastructure.share.DefaultChatShareAccessPolicy;
import com.huawei.it.ex.one.interfaces.ApiExceptionHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class ChatShareControllerTest {
    private static final UserContext USER = new UserContext("tenant1", "user1", "tester");
    private static final String WEB_PREFIX = "https://example.com/#/snapshot?shareId=";
    private static final String MOBILE_PREFIX = "h5://S013536/html/index.html#/share?shareId=";
    private final ChatShareApplicationService shares = mock(ChatShareApplicationService.class);
    private final ChatShareRepository repository = mock(ChatShareRepository.class);
    private final ChatShareDeliveryRepository deliveries = mock(ChatShareDeliveryRepository.class);
    private final ChatShareDeliveryProvider provider = mock(ChatShareDeliveryProvider.class);
    private final ChatShareDeliveryProperties properties = new ChatShareDeliveryProperties();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        properties.setShareUrlPrefix(WEB_PREFIX);
        properties.setMobileShareUrlPrefix(MOBILE_PREFIX);
        when(provider.providerCode()).thenReturn("welink");
        when(provider.deliver(any())).thenReturn(ChatShareProviderDeliveryResult.success(Map.of("status", "200")));
        when(deliveries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ChatShareDeliveryApplicationService deliveryService = new ChatShareDeliveryApplicationService(
                repository, deliveries, new ChatShareDeliveryProviderRegistry(List.of(provider)),
                (type, context) -> "delivery1", new PermissionChecker(), new DefaultChatShareAccessPolicy(), properties);
        AuthContextProvider auth = mock(AuthContextProvider.class);
        when(auth.resolve()).thenReturn(USER);
        mvc = MockMvcBuilders.standaloneSetup(new ChatShareController(shares,
                        mock(SelectedChatShareApplicationService.class), deliveryService,
                        auth, new PermissionChecker(), properties))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({
            "/v1/chat/shares/share1/deliveries, SINGLE_TURN, false",
            "/v1/chat/shares/share1/deliveries, SELECTED_MESSAGES, false",
            "/v1/chat/messages/msg_assistant/share/deliveries, SINGLE_TURN, true"
    })
    void bothDeliveryEndpointsReturnTheExactSentAndSavedLink(String path, String scope, boolean create)
            throws Exception {
        ChatShare share = share(scope);
        when(repository.findById("share1")).thenReturn(Optional.of(share));
        when(shares.create(eq(USER), any())).thenReturn(share);

        MvcResult started = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "sid=test")
                .content("{\"provider\":\"welink\",\"targetAccounts\":[\"receiver\"]}")).andReturn();
        String link = WEB_PREFIX + "share1&welink_open_uri=" + Base64.getEncoder()
                .encodeToString((MOBILE_PREFIX + "share1").getBytes(StandardCharsets.UTF_8));
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(jsonPath(create ? "$.delivery.linkUrl" : "$.linkUrl").value(link));

        ArgumentCaptor<ChatShareProviderDeliveryRequest> outbound =
                ArgumentCaptor.forClass(ChatShareProviderDeliveryRequest.class);
        ArgumentCaptor<ChatShareDelivery> saved = ArgumentCaptor.forClass(ChatShareDelivery.class);
        verify(provider).deliver(outbound.capture());
        verify(deliveries).save(saved.capture());
        assertThat(outbound.getValue().linkUrl()).isEqualTo(link);
        assertThat(saved.getValue().linkUrl()).isEqualTo(link);
        assertThat(outbound.getValue().forwardHeaders().cookieHeader()).isEqualTo("sid=test");
        if (create) {
            verify(shares).create(eq(USER), any());
        } else {
            verifyNoInteractions(shares);
        }
    }

    @Test
    void creatingOnlyTheShareStillReturnsMetadataWithoutLinkOrDelivery() throws Exception {
        when(shares.create(eq(USER), any())).thenReturn(share("SINGLE_TURN"));

        MvcResult started = mvc.perform(post("/v1/chat/messages/msg_assistant/share")).andReturn();

        mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shareId").value("share1"))
                .andExpect(jsonPath("$.linkUrl").doesNotExist());
        verifyNoInteractions(repository, deliveries);
        verify(provider, never()).deliver(any());
    }

    @Test
    void oversizedMobileLinkReturnsTheExistingConflictResponseBeforeSending() throws Exception {
        when(repository.findById("share1")).thenReturn(Optional.of(share("SINGLE_TURN")));
        properties.setMobileShareUrlPrefix("h5://app/" + "a".repeat(1024));

        MvcResult started = mvc.perform(post("/v1/chat/shares/share1/deliveries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"welink\",\"targetAccounts\":[\"receiver\"]}")).andReturn();

        mvc.perform(asyncDispatch(started)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        verify(provider, never()).deliver(any());
        verifyNoInteractions(deliveries);
    }

    private ChatShare share(String scope) {
        return new ChatShare("share1", "tenant1", "user1", "session1", "msg_user", "msg_assistant",
                "run1", "title", scope, "INTERNAL", "ACTIVE", null, null, null, Instant.EPOCH, Instant.EPOCH);
    }
}
