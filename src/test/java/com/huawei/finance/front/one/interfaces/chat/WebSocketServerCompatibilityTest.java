package com.huawei.finance.front.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

class WebSocketServerCompatibilityTest {
    @Test
    void webFluxAndServletHandlersAreActivatedOnlyInMatchingServerStack() {
        assertThat(ChatWebSocketConfig.class.getAnnotation(ConditionalOnWebApplication.class).type())
                .isEqualTo(ConditionalOnWebApplication.Type.REACTIVE);
        assertThat(ChatWebSocketHandler.class.getAnnotation(ConditionalOnWebApplication.class).type())
                .isEqualTo(ConditionalOnWebApplication.Type.REACTIVE);

        assertThat(ChatServletWebSocketConfig.class.getAnnotation(ConditionalOnWebApplication.class).type())
                .isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
        assertThat(ChatServletWebSocketHandler.class.getAnnotation(ConditionalOnWebApplication.class).type())
                .isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
    }

    @Test
    void webFluxAndServletHandlersExposeSameFrontendWebSocketPath() throws Exception {
        String reactiveConfig = Files.readString(Path.of(
                "src/main/java/com/huawei/finance/front/one/interfaces/chat/ChatWebSocketConfig.java"));
        String servletConfig = Files.readString(Path.of(
                "src/main/java/com/huawei/finance/front/one/interfaces/chat/ChatServletWebSocketConfig.java"));

        assertThat(reactiveConfig).contains("/api/v1/ex/chat/ws");
        assertThat(servletConfig).contains("/api/v1/ex/chat/ws");
    }

    @Test
    void servletWebSocketUsesHandshakeInterceptorForThreadLocalIdentity() throws Exception {
        String servletConfig = Files.readString(Path.of(
                "src/main/java/com/huawei/finance/front/one/interfaces/chat/ChatServletWebSocketConfig.java"));
        String servletHandler = Files.readString(Path.of(
                "src/main/java/com/huawei/finance/front/one/interfaces/chat/ChatServletWebSocketHandler.java"));
        String protocolService = Files.readString(Path.of(
                "src/main/java/com/huawei/finance/front/one/interfaces/chat/ChatWebSocketProtocolService.java"));

        assertThat(servletConfig).contains(".addInterceptors(authInterceptor)");
        assertThat(servletHandler).contains("ChatWebSocketUserContextAttributes.require(session.getAttributes())");
        assertThat(protocolService)
                .doesNotContain("application.integration.identity.AuthContextProvider")
                .doesNotContain("private final AuthContextProvider")
                .doesNotContain("auth.resolve()");
    }
}
