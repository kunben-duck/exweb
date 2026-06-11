package com.huawei.finance.front.one.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.config.DocumentProviderProperties;
import com.huawei.finance.front.one.application.config.ResourceIsolationProperties;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.document.DocumentProviderUploadRequest;
import com.huawei.finance.front.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
class HttpDocumentProviderAdapterTest {
    private static final String COOKIE = "sid=abc; theme=dark";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void forwardsCookieHeaderToTrustedHttpProviderUpload() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        HttpDocumentProviderAdapter adapter = adapter(captured, """
                {"data":[{"docid":"legacy-doc-1","docname":"invoice.pdf","docsize":3,
                "levelCode":"IP","serverName":"shenzhen","version":"V1"}]}
                """);
        DocumentProviderProperties.ProviderEntry provider = provider(true);

        UploadedDocument document = adapter.upload(uploadRequest(provider, COOKIE));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE)).isEqualTo(COOKIE);
        assertThat(document.objectKey()).isEqualTo("legacy-doc-1");
        assertThat(document.source()).isEqualTo("LEGACY_AGENT_UPLOAD");
        JsonNode metadata = objectMapper.readTree(document.metadataJson());
        assertThat(metadata.at("/providerDocument/providerLocatorType").asText()).isEqualTo("DOC_ID");
        assertThat(metadata.at("/providerDocument/docId").asText()).isEqualTo("legacy-doc-1");
        assertThat(document.metadataJson())
                .contains("\"providerCode\":\"legacy-agent\"")
                .contains("\"docId\":\"legacy-doc-1\"")
                .doesNotContain(COOKIE)
                .doesNotContain("cookieHeader");
    }

    @Test
    void doesNotForwardCookieWhenProviderDisablesCookieForwarding() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        HttpDocumentProviderAdapter adapter = adapter(captured, docIdResponse());
        DocumentProviderProperties.ProviderEntry provider = provider(false);

        UploadedDocument document = adapter.upload(uploadRequest(provider, COOKIE));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers()).doesNotContainKey(HttpHeaders.COOKIE);
        assertThat(document.metadataJson()).doesNotContain(COOKIE);
    }

    @Test
    void doesNotForwardCookieWhenUploadCommandHasNoCookieSnapshot() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        HttpDocumentProviderAdapter adapter = adapter(captured, docIdResponse());
        DocumentProviderProperties.ProviderEntry provider = provider(true);

        adapter.upload(uploadRequest(provider, null));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers()).doesNotContainKey(HttpHeaders.COOKIE);
    }

    @Test
    void acceptsUrlOnlyUploadResponseAndStoresStableLocatorInsteadOfRawUrl() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String url = "https://legacy.example/files/invoice.pdf?ticket=abc";
        HttpDocumentProviderAdapter adapter = adapter(captured, """
                {"data":[{"url":"%s","docname":"invoice.pdf","docsize":3}]}
                """.formatted(url));
        DocumentProviderProperties.ProviderEntry provider = provider(true);

        UploadedDocument document = adapter.upload(uploadRequest(provider, null));

        assertThat(document.objectKey()).startsWith("legacy-url:");
        assertThat(document.objectKey()).doesNotContain(url);
        JsonNode metadata = objectMapper.readTree(document.metadataJson());
        assertThat(metadata.at("/providerDocument/providerLocatorType").asText()).isEqualTo("URL");
        assertThat(metadata.at("/providerDocument/url").asText()).isEqualTo(url);
        assertThat(metadata.at("/providerDocument/docId").isNull()).isTrue();
    }

    @Test
    void rejectsUploadResponseWithoutDocumentIdOrUrl() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        HttpDocumentProviderAdapter adapter = adapter(captured, """
                {"data":[{"docname":"invoice.pdf","docsize":3}]}
                """);
        DocumentProviderProperties.ProviderEntry provider = provider(true);

        assertThatThrownBy(() -> adapter.upload(uploadRequest(provider, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档 ID 或 URL");
    }

    private HttpDocumentProviderAdapter adapter(AtomicReference<ClientRequest> captured, String responseBody) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(responseBody)
                            .build());
                });
        return new HttpDocumentProviderAdapter(builder, objectMapper,
                new WorkloadConcurrencyLimiter(new ResourceIsolationProperties()));
    }

    private String docIdResponse() {
        return """
                {"data":[{"docid":"legacy-doc-1","docname":"invoice.pdf","docsize":3,
                "levelCode":"IP","serverName":"shenzhen","version":"V1"}]}
                """;
    }

    private DocumentProviderUploadRequest uploadRequest(DocumentProviderProperties.ProviderEntry provider,
                                                        String cookieHeader) {
        RuntimeForwardHeaders forwardHeaders = cookieHeader == null
                ? RuntimeForwardHeaders.empty()
                : RuntimeForwardHeaders.fromCookieHeader(cookieHeader, 8192);
        DocumentUploadCommand command = new DocumentUploadCommand(
                "session1",
                "invoice.pdf",
                "application/pdf",
                3,
                new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8)),
                "legacy-agent",
                "skill-tax",
                "{\"clientTraceId\":\"trace-1\"}",
                forwardHeaders
        );
        return new DocumentProviderUploadRequest(
                new UserContext("tenant1", "user1", "Alice"),
                "doc1",
                "legacy-agent",
                provider,
                command
        );
    }

    private DocumentProviderProperties.ProviderEntry provider(boolean forwardCookie) {
        DocumentProviderProperties.ProviderEntry provider = new DocumentProviderProperties.ProviderEntry();
        provider.setType("http");
        provider.setEnabled(true);
        provider.setSource("LEGACY_AGENT_UPLOAD");
        provider.setBaseUrl("http://legacy.test");
        provider.setForwardCookie(forwardCookie);
        provider.getUpload().setEnabled(true);
        provider.getUpload().setPath("/upload");
        provider.getUpload().setMethod("POST");
        provider.getUpload().setContentType("multipart");
        provider.getUpload().setFileField("file");
        provider.getUpload().setExtraFormFields(Map.of("skillId", "${skillId}", "sessionId", "${sessionId}"));
        return provider;
    }
}
