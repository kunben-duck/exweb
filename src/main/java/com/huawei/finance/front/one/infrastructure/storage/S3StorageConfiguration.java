package com.huawei.finance.front.one.infrastructure.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * S3 客户端配置。
 *
 * <p>仅当 financeex.storage.provider=s3 时生效。显式配置 access-key/secret-key 时使用静态凭证；
 * 未配置时使用 AWS SDK 默认凭证链，便于在生产环境通过 IAM Role、容器角色或实例角色获取凭证。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "s3")
public class S3StorageConfiguration {
    @Bean
    public S3Client financeExS3Client(@Value("${financeex.storage.s3.region:}") String region,
                                      @Value("${financeex.storage.s3.endpoint:}") String endpoint,
                                      @Value("${financeex.storage.s3.access-key:}") String accessKey,
                                      @Value("${financeex.storage.s3.secret-key:}") String secretKey,
                                      @Value("${financeex.storage.s3.path-style-access-enabled:false}") boolean pathStyleAccessEnabled) {
        S3ClientBuilder builder = S3Client.builder()
                // 使用 JDK URLConnection 客户端，避免引入 apache-client 的 commons-logging 冲突。
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(requireText(region, "S3 region 不能为空")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccessEnabled)
                        .build());

        if (hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
        if (hasText(accessKey) || hasText(secretKey)) {
            if (!hasText(accessKey) || !hasText(secretKey)) {
                throw new IllegalStateException("S3 access-key 和 secret-key 必须同时配置");
            }
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    private static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
