package com.huawei.finance.front.one.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.domain.document.StoredObject;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class S3ObjectStorageTest {
    @Test
    void writesObjectToTenantScopedS3Key() {
        FakeS3Client s3Client = new FakeS3Client();
        S3ObjectStorage storage = new S3ObjectStorage(s3Client, "financeex-documents", "uploads");

        StoredObject stored = storage.putObject("tenant/a", "../invoice image.pdf", "application/pdf", 3,
                new ByteArrayInputStream(new byte[] {1, 2, 3}));

        PutObjectRequest request = s3Client.lastRequest;
        assertThat(request.bucket()).isEqualTo("financeex-documents");
        assertThat(request.key()).startsWith("uploads/tenant_a/");
        assertThat(request.key()).endsWith("-invoice_image.pdf");
        assertThat(request.key()).doesNotContain("..");
        assertThat(request.contentType()).isEqualTo("application/pdf");
        assertThat(stored.bucket()).isEqualTo("financeex-documents");
        assertThat(stored.objectKey()).isEqualTo(request.key());
        assertThat(stored.sizeBytes()).isEqualTo(3);
    }

    private static class FakeS3Client implements S3Client {
        private PutObjectRequest lastRequest;
        private RequestBody lastBody;

        @Override
        public PutObjectResponse putObject(PutObjectRequest putObjectRequest, RequestBody requestBody) {
            this.lastRequest = putObjectRequest;
            this.lastBody = requestBody;
            return PutObjectResponse.builder().eTag("etag").build();
        }

        @Override
        public String serviceName() {
            return S3Client.SERVICE_NAME;
        }

        @Override
        public void close() {
            // fake client has no resources to close
        }
    }
}
