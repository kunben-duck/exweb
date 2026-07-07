package com.huawei.finance.front.one.infrastructure.storage.object.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.domain.document.StoredObject;
import com.obs.services.ObsClient;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 华为 OBS S3 集成测试。
 *
 * <p>该测试只在显式提供 {@code FINANCEEX_HUAWEI_S3_TEST_ENDPOINT} 等环境变量时执行，
 * 避免本地和 CI 因没有真实对象存储账号而失败。</p>
 */
class HuaweiS3ObjectStorageIntegrationTest {
    @Test
    void writesAndReadsObjectFromHuaweiS3WhenConfigured() throws Exception {
        String endpointValue = System.getenv("FINANCEEX_HUAWEI_S3_TEST_ENDPOINT");
        Assumptions.assumeTrue(endpointValue != null && !endpointValue.isBlank(), "未配置 Huawei S3 测试 endpoint，跳过集成测试");

        URI endpoint = URI.create(endpointValue);
        String bucket = requireEnv("FINANCEEX_HUAWEI_S3_TEST_BUCKET");
        String accessKey = requireEnv("FINANCEEX_HUAWEI_S3_TEST_ACCESS_KEY");
        String secretKey = requireEnv("FINANCEEX_HUAWEI_S3_TEST_SECRET_KEY");
        try (ObsClient obsClient = new ObsClient(accessKey, secretKey, endpoint.toString())) {
            HuaweiS3ObjectStorage storage = new HuaweiS3ObjectStorage(new HuaweiObsClientOperations(obsClient), bucket, "integration-test");
            byte[] content = "hello-huawei-s3".getBytes(StandardCharsets.UTF_8);

            StoredObject stored = storage.putObject("tenant-a", "hello.txt", "text/plain", content.length,
                    new ByteArrayInputStream(content));

            try (var object = storage.getObject(bucket, stored.objectKey()).inputStream()) {
                assertThat(new String(object.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello-huawei-s3");
            } finally {
                obsClient.deleteObject(bucket, stored.objectKey());
            }
        }
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), "未配置 " + name + "，跳过集成测试");
        return value.trim();
    }
}
