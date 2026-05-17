package com.huawei.finance.front.one.infrastructure.storage;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 华为 OBS S3 客户端配置。
 *
 * <p>仅当 {@code financeex.storage.provider=huawei-s3} 时生效。当前正式实现使用华为 OBS Java SDK。
 * access-key、secret-key 与 endpoint 必须显式配置，避免生产环境
 * 因隐式凭证链造成不可预期的租户或账号选择。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "huawei-s3")
public class HuaweiS3StorageConfiguration {
    @Bean(destroyMethod = "close")
    public ObsClient financeExHuaweiObsClient(@Value("${financeex.storage.huawei-s3.endpoint:}") String endpoint,
                                              @Value("${financeex.storage.huawei-s3.access-key:}") String accessKey,
                                              @Value("${financeex.storage.huawei-s3.secret-key:}") String secretKey,
                                              @Value("${financeex.storage.huawei-s3.path-style-access-enabled:false}") boolean pathStyleAccessEnabled,
                                              @Value("${financeex.storage.huawei-s3.max-connections:200}") int maxConnections,
                                              @Value("${financeex.storage.huawei-s3.connection-timeout-ms:10000}") int connectionTimeoutMs,
                                              @Value("${financeex.storage.huawei-s3.socket-timeout-ms:30000}") int socketTimeoutMs) {
        ObsConfiguration configuration = new ObsConfiguration();
        configuration.setEndPoint(requireText(endpoint, "Huawei S3 endpoint 不能为空"));
        configuration.setPathStyle(pathStyleAccessEnabled);
        configuration.setMaxConnections(maxConnections);
        configuration.setConnectionTimeout(connectionTimeoutMs);
        configuration.setSocketTimeout(socketTimeoutMs);
        return new ObsClient(
                requireText(accessKey, "Huawei S3 access-key 不能为空"),
                requireText(secretKey, "Huawei S3 secret-key 不能为空"),
                configuration);
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
