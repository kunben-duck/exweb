package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.service.chat.DomainAgentAsyncCallbackAdmission;
import com.huawei.it.ex.one.interfaces.chat.dto.DomainAgentAsyncTaskCallbackRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import jakarta.servlet.http.HttpServletRequestWrapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

class DomainAgentAsyncTaskCallbackAdmissionFilterTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rejectsKnownOversizedBodyBeforeInvokingDownstreamChain() throws Exception {
        DomainAgentProperties properties = properties(8, 1);
        DomainAgentAsyncCallbackAdmission admission = new DomainAgentAsyncCallbackAdmission(properties);
        DomainAgentAsyncTaskCallbackAdmissionFilter filter =
                new DomainAgentAsyncTaskCallbackAdmissionFilter(admission, properties, objectMapper);
        MockHttpServletRequest request = callbackRequest("123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(responseCode(response)).isEqualTo("DOMAIN_AGENT_ASYNC_CALLBACK_TOO_LARGE");

        AtomicBoolean nextInvoked = new AtomicBoolean();
        filter.doFilter(callbackRequest("{}"), new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> nextInvoked.set(true));
        assertThat(nextInvoked).isTrue();
    }

    @Test
    void protectsMatrixParameterAndContextPathVariantsMatchedBySpringMvc() throws Exception {
        DomainAgentProperties properties = properties(8, 1);
        DomainAgentAsyncCallbackAdmission admission = new DomainAgentAsyncCallbackAdmission(properties);
        DomainAgentAsyncTaskCallbackAdmissionFilter filter =
                new DomainAgentAsyncTaskCallbackAdmissionFilter(admission, properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/chat" + DomainAgentAsyncTaskCallbackAdmissionFilter.CALLBACK_PATH + ";x=1");
        request.setContextPath("/chat");
        request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(responseCode(response)).isEqualTo("DOMAIN_AGENT_ASYNC_CALLBACK_TOO_LARGE");
    }

    @Test
    void countsChunkedBodyBeforeJsonDeserialization() throws Exception {
        DomainAgentProperties properties = properties(8, 1);
        DomainAgentAsyncCallbackAdmission admission = new DomainAgentAsyncCallbackAdmission(properties);
        DomainAgentAsyncTaskCallbackAdmissionFilter filter =
                new DomainAgentAsyncTaskCallbackAdmissionFilter(admission, properties, objectMapper);
        MockHttpServletRequest base = callbackRequest("123456789");
        HttpServletRequestWrapper request = new HttpServletRequestWrapper(base) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (boundedRequest, ignoredResponse) -> {
            invoked.set(true);
            boundedRequest.getInputStream().readAllBytes();
        });

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(responseCode(response)).isEqualTo("DOMAIN_AGENT_ASYNC_CALLBACK_TOO_LARGE");
    }

    @Test
    void rejectsImmediatelyWhenCallbackCapacityIsFullAndReleasesPermit() throws Exception {
        DomainAgentProperties properties = properties(1024, 1);
        DomainAgentAsyncCallbackAdmission admission = new DomainAgentAsyncCallbackAdmission(properties);
        DomainAgentAsyncTaskCallbackAdmissionFilter filter =
                new DomainAgentAsyncTaskCallbackAdmissionFilter(admission, properties, objectMapper);
        DomainAgentAsyncCallbackAdmission.Permit occupied = admission.tryAcquire();
        MockHttpServletResponse busyResponse = new MockHttpServletResponse();
        AtomicBoolean busyInvoked = new AtomicBoolean();

        filter.doFilter(callbackRequest("{}"), busyResponse,
                (ignoredRequest, ignoredResponse) -> busyInvoked.set(true));

        assertThat(busyInvoked).isFalse();
        assertThat(busyResponse.getStatus()).isEqualTo(429);
        assertThat(responseCode(busyResponse)).isEqualTo("DOMAIN_AGENT_ASYNC_CALLBACK_BUSY");

        occupied.close();
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        AtomicBoolean acceptedInvoked = new AtomicBoolean();
        filter.doFilter(callbackRequest("{}"), acceptedResponse,
                (ignoredRequest, ignoredResponse) -> acceptedInvoked.set(true));

        assertThat(acceptedInvoked).isTrue();
    }

    @Test
    void holdsPermitUntilServletAsyncRequestCompletes() throws Exception {
        DomainAgentProperties properties = properties(1024, 1);
        DomainAgentAsyncCallbackAdmission admission = new DomainAgentAsyncCallbackAdmission(properties);
        DomainAgentAsyncTaskCallbackAdmissionFilter filter =
                new DomainAgentAsyncTaskCallbackAdmissionFilter(admission, properties, objectMapper);
        MockHttpServletRequest asyncRequest = callbackRequest("{}");
        asyncRequest.setAsyncSupported(true);

        filter.doFilter(asyncRequest, new MockHttpServletResponse(),
                (request, response) -> request.startAsync(request, response));

        MockHttpServletResponse busyResponse = new MockHttpServletResponse();
        filter.doFilter(callbackRequest("{}"), busyResponse,
                (ignoredRequest, ignoredResponse) -> { });
        assertThat(busyResponse.getStatus()).isEqualTo(429);

        asyncRequest.getAsyncContext().complete();
        AtomicBoolean acceptedAfterCompletion = new AtomicBoolean();
        filter.doFilter(callbackRequest("{}"), new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> acceptedAfterCompletion.set(true));
        assertThat(acceptedAfterCompletion).isTrue();
    }

    @Test
    void acceptsOnlyTextErrorDuringJsonDeserialization() throws Exception {
        DomainAgentAsyncTaskCallbackRequest request = objectMapper.readValue(
                "{\"runId\":\"run1\",\"status\":\"FAILED\",\"error\":\"failed\"}",
                DomainAgentAsyncTaskCallbackRequest.class);

        assertThat(request.error()).isEqualTo("failed");
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"runId\":\"run1\",\"status\":\"FAILED\",\"error\":{\"code\":\"E1\"}}",
                DomainAgentAsyncTaskCallbackRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"runId\":\"run1\",\"status\":\"FAILED\",\"error\":[\"E1\"]}",
                DomainAgentAsyncTaskCallbackRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    private DomainAgentProperties properties(int maxRequestBytes, int concurrency) {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setAsyncTaskCallbackRequestMaxBytes(maxRequestBytes);
        properties.setAsyncTaskCallbackMaxConcurrency(concurrency);
        return properties;
    }

    private MockHttpServletRequest callbackRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", DomainAgentAsyncTaskCallbackAdmissionFilter.CALLBACK_PATH);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }

    private String responseCode(MockHttpServletResponse response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        return body.path("code").asText();
    }
}
