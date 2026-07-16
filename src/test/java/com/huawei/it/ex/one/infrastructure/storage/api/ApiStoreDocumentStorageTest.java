package com.huawei.it.ex.one.infrastructure.storage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.command.DocumentUploadCommand;
import com.huawei.it.ex.one.application.config.ResourceIsolationProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.document.DocumentStorageUploadRequest;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.document.DocumentSource;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ApiStoreDocumentStorageTest {
    private static final String COOKIE = "sid=abc; theme=dark";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void uploadWithoutSkillIdSendsOnlyFileAndStoresUrlDocument() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"traceId":"t1","status":"success","data":[{"docName":"big-logo.png",
                "url":"http://s3.example/big-logo.png"}]}
                """, false);

        UploadedDocument document = storage.upload(request(null, null));

        assertThat(captured.get()).isNotNull();
        String body = capturedBody(captured.get());
        assertThat(body).contains("name=\"file\"");
        assertThat(body).doesNotContain("name=\"skillId\"");
        assertThat(document.bucket()).isEqualTo("api-store");
        assertThat(document.objectKey()).startsWith("api-store-url:");
        assertThat(document.source()).isEqualTo(DocumentSource.S3_UPLOAD.name());
        JsonNode metadata = objectMapper.readTree(document.metadataJson());
        assertThat(metadata.at("/providerDocument/providerLocatorType").asText()).isEqualTo("URL");
        assertThat(metadata.at("/providerDocument/url").asText()).isEqualTo("http://s3.example/big-logo.png");
        assertThat(metadata.at("/skillId").isNull()).isTrue();
    }

    @Test
    void uploadWithEmptyMetadataSkillIdStillSendsSkillIdPart() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"traceId":"t2","status":"success","data":[{"docId":"M3T1","docName":"deck.pptx",
                "docSize":"15887275","docStatus":0,"serverName":"ShenZhen","docVersion":"V1"}]}
                """, false);

        UploadedDocument document = storage.upload(request("{\"skillId\":\"\",\"clientTraceId\":\"trace-1\"}", null));

        assertThat(captured.get()).isNotNull();
        String body = capturedBody(captured.get());
        assertThat(body).contains("name=\"file\"");
        assertThat(body).contains("name=\"skillId\"");
        assertThat(document.source()).isEqualTo(DocumentSource.EDM_UPLOAD.name());
        JsonNode metadata = objectMapper.readTree(document.metadataJson());
        assertThat(metadata.at("/skillId").asText()).isEmpty();
        assertThat(metadata.at("/uploadMetadata/skillId").asText()).isEmpty();
        assertThat(metadata.at("/providerDocument/docId").asText()).isEqualTo("M3T1");
    }

    @Test
    void uploadWithMissingOrNullMetadataSkillIdDoesNotSendSkillIdPart() throws Exception {
        AtomicReference<ClientRequest> missingCaptured = new AtomicReference<>();
        ApiStoreDocumentStorage missingStorage = storage(missingCaptured, """
                {"traceId":"t1","status":"success","data":[{"docName":"big-logo.png",
                "url":"http://s3.example/big-logo.png"}]}
                """, false);

        UploadedDocument missing = missingStorage.upload(request("{\"clientTraceId\":\"trace-1\"}", null));

        assertThat(capturedBody(missingCaptured.get())).doesNotContain("name=\"skillId\"");
        assertThat(missing.source()).isEqualTo(DocumentSource.S3_UPLOAD.name());
        assertThat(objectMapper.readTree(missing.metadataJson()).at("/skillId").isNull()).isTrue();

        AtomicReference<ClientRequest> nullCaptured = new AtomicReference<>();
        ApiStoreDocumentStorage nullStorage = storage(nullCaptured, """
                {"traceId":"t1","status":"success","data":[{"docName":"big-logo.png",
                "url":"http://s3.example/big-logo.png"}]}
                """, false);

        UploadedDocument nullSkillId = nullStorage.upload(request("{\"skillId\":null}", null));

        assertThat(capturedBody(nullCaptured.get())).doesNotContain("name=\"skillId\"");
        assertThat(nullSkillId.source()).isEqualTo(DocumentSource.S3_UPLOAD.name());
        assertThat(objectMapper.readTree(nullSkillId.metadataJson()).at("/skillId").isNull()).isTrue();
    }

    @Test
    void storesEdmSourceFromDocIdWithoutDependingOnSkillId() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"status":"success","data":[{"docId":"EDM-001","docName":"deck.pptx"}]}
                """, false);

        UploadedDocument document = storage.upload(request(null, null));

        assertThat(capturedBody(captured.get())).doesNotContain("name=\"skillId\"");
        assertThat(document.source()).isEqualTo(DocumentSource.EDM_UPLOAD.name());
        assertThat(document.objectKey()).isEqualTo("EDM-001");
        JsonNode metadata = objectMapper.readTree(document.metadataJson());
        assertThat(metadata.at("/providerDocument/providerLocatorType").asText()).isEqualTo("DOC_ID");
    }

    @Test
    void storesS3SourceWhenResponseUsesUrlEvenIfSkillIdWasSent() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"status":"success","data":[{"url":"https://s3.example/deck.pptx","docName":"deck.pptx"}]}
                """, false);

        UploadedDocument document = storage.upload(request("{\"skillId\":\"skill-1\"}", null));

        assertThat(capturedBody(captured.get())).contains("name=\"skillId\"");
        assertThat(document.source()).isEqualTo(DocumentSource.S3_UPLOAD.name());
        assertThat(document.objectKey()).startsWith("api-store-url:");
        JsonNode metadata = objectMapper.readTree(document.metadataJson());
        assertThat(metadata.at("/providerDocument/providerLocatorType").asText()).isEqualTo("URL");
    }

    @Test
    void prefersEdmSourceAndDocIdWhenResponseContainsDocIdAndUrl() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"status":"success","data":[{"docId":"EDM-002","url":"https://s3.example/deck.pptx",
                "docName":"deck.pptx"}]}
                """, false);

        UploadedDocument document = storage.upload(request(null, null));

        assertThat(document.source()).isEqualTo(DocumentSource.EDM_UPLOAD.name());
        assertThat(document.objectKey()).isEqualTo("EDM-002");
        JsonNode metadata = objectMapper.readTree(document.metadataJson());
        assertThat(metadata.at("/providerDocument/providerLocatorType").asText()).isEqualTo("DOC_ID");
        assertThat(metadata.at("/providerDocument/docId").asText()).isEqualTo("EDM-002");
        assertThat(metadata.at("/providerDocument/url").asText()).isEqualTo("https://s3.example/deck.pptx");
    }

    @Test
    void uploadWithMetadataSkillIdSendsSkillIdAndStoresEdmDocument() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"traceId":"t2","status":"success","data":[{"docId":"M3T1","docName":"deck.pptx",
                "docSize":"15887275","docStatus":0,"serverName":"ShenZhen","docVersion":"V1"}]}
                """, true);

        UploadedDocument document = storage.upload(request("{\"skillId\":\"skill-1\",\"clientTraceId\":\"trace-1\"}", COOKIE));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE)).isEqualTo(COOKIE);
        String body = capturedBody(captured.get());
        assertThat(body).contains("name=\"file\"");
        assertThat(body).contains("name=\"skillId\"");
        assertThat(body).contains("skill-1");
        assertThat(document.objectKey()).isEqualTo("M3T1");
        assertThat(document.originalName()).isEqualTo("deck.pptx");
        assertThat(document.sizeBytes()).isEqualTo(15887275L);
        assertThat(document.source()).isEqualTo(DocumentSource.EDM_UPLOAD.name());
        JsonNode metadata = objectMapper.readTree(document.metadataJson());
        assertThat(metadata.at("/skillId").asText()).isEqualTo("skill-1");
        assertThat(metadata.at("/providerDocument/docId").asText()).isEqualTo("M3T1");
        assertThat(metadata.at("/providerDocument/serverName").asText()).isEqualTo("ShenZhen");
        assertThat(metadata.at("/providerDocument/docVersion").asText()).isEqualTo("V1");
        assertThat(metadata.at("/uploadMetadata/clientTraceId").asText()).isEqualTo("trace-1");
        assertThat(document.metadataJson()).doesNotContain(COOKIE);
    }

    @Test
    void rejectsNonStringMetadataSkillId() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"traceId":"t1","status":"success","data":[{"docName":"big-logo.png",
                "url":"http://s3.example/big-logo.png"}]}
                """, false);

        assertThatThrownBy(() -> storage.upload(request("{\"skillId\":123}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata.skillId 必须是字符串");
    }

    @Test
    void rejectsInvalidMetadataJsonAndPreservesParsingCause() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, "{}", false);

        assertThatThrownBy(() -> storage.upload(request("{", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata 必须是合法 JSON 字符串")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void rejectsFailedApiStoreResponse() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"status":"failed","message":"invalid file","data":[]}
                """, false);

        assertThatThrownBy(() -> storage.upload(request(null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api-store 上传失败");
    }

    @Test
    void rejectsResponseWithoutDocumentIdOrUrl() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ApiStoreDocumentStorage storage = storage(captured, """
                {"status":"success","data":[{"docName":"empty.pdf"}]}
                """, false);

        assertThatThrownBy(() -> storage.upload(request(null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docId 或 url");
    }

    private ApiStoreDocumentStorage storage(AtomicReference<ClientRequest> captured, String responseBody,
                                            boolean forwardCookie) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(responseBody)
                            .build());
                });
        ApiStoreStorageProperties properties = new ApiStoreStorageProperties();
        properties.setBaseUrl("https://gce-b7.mfg.huawei.com");
        properties.setUploadPath("/fina/agent/fileOperate/upload");
        properties.setForwardCookie(forwardCookie);
        return new ApiStoreDocumentStorage(builder, objectMapper,
                new WorkloadConcurrencyLimiter(new ResourceIsolationProperties()), properties);
    }

    private DocumentStorageUploadRequest request(String metadataJson, String cookieHeader) {
        RuntimeForwardHeaders forwardHeaders = cookieHeader == null
                ? RuntimeForwardHeaders.empty()
                : RuntimeForwardHeaders.fromCookieHeader(cookieHeader, 8192);
        DocumentUploadCommand command = new DocumentUploadCommand(
                "session1",
                "deck.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                3,
                new ByteArrayInputStream("ppt".getBytes(StandardCharsets.UTF_8)),
                metadataJson,
                forwardHeaders
        );
        return new DocumentStorageUploadRequest(new UserContext("tenant1", "user1", "Alice"), "doc1", command);
    }

    private String capturedBody(ClientRequest request) {
        CapturingClientHttpRequest output = new CapturingClientHttpRequest(request.url());
        request.body().insert(output, new org.springframework.web.reactive.function.BodyInserter.Context() {
            @Override
            public java.util.List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Map<String, Object> hints() {
                return java.util.Map.of();
            }
        }).block();
        return output.bodyAsString();
    }
}
