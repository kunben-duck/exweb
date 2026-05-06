package com.huawei.finance.front.one.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.domain.document.StoredObject;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * MinIO 集成测试。
 *
 * <p>默认不强制依赖 Docker；当本地 9000 端口有 MinIO 时，该测试会真实写入并读回对象。
 * 启动方式：docker compose up -d minio minio-init。</p>
 */
class S3ObjectStorageMinioIntegrationTest {
    @Test
    void writesAndReadsObjectFromMinioWhenAvailable() throws Exception {
        URI endpoint = URI.create(env("FINANCEEX_MINIO_TEST_ENDPOINT", "http://localhost:9000"));
        Assumptions.assumeTrue(isReachable(endpoint), "MinIO 未启动，跳过集成测试");

        String bucket = env("FINANCEEX_MINIO_TEST_BUCKET", "financeex-documents");
        try (S3Client s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(env("FINANCEEX_MINIO_TEST_REGION", "us-east-1")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        env("FINANCEEX_MINIO_TEST_ACCESS_KEY", "fin_supervisor"),
                        env("FINANCEEX_MINIO_TEST_SECRET_KEY", "kunone123"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            ensureBucket(s3Client, bucket);
            S3ObjectStorage storage = new S3ObjectStorage(s3Client, bucket, "integration-test");
            byte[] content = "hello-minio".getBytes(StandardCharsets.UTF_8);

            StoredObject stored = storage.putObject("tenant-a", "hello.txt", "text/plain", content.length,
                    new ByteArrayInputStream(content));

            try (ResponseInputStream<GetObjectResponse> object = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(stored.objectKey())
                    .build())) {
                assertThat(new String(object.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello-minio");
            } finally {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(stored.objectKey())
                        .build());
            }
        }
    }

    private void ensureBucket(S3Client s3Client, String bucket) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException ex) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                return;
            }
            throw ex;
        }
    }

    private boolean isReachable(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, endpoint.getPort() > 0 ? endpoint.getPort() : defaultPort(endpoint)), 500);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private int defaultPort(URI endpoint) {
        return "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
