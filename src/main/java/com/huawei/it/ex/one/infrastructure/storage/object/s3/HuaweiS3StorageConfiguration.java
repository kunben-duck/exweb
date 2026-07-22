package com.huawei.it.ex.one.infrastructure.storage.object.s3;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(HuaweiS3StorageProperties.class)
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "huawei-s3")
public class HuaweiS3StorageConfiguration {
    @Bean(destroyMethod = "close")
    public ObsClient financeExHuaweiObsClient(HuaweiS3StorageProperties properties) {
        ObsConfiguration configuration = new ObsConfiguration();
        configuration.setEndPoint(requireText(properties.getEndpoint(), "Huawei S3 endpoint 不能为空"));
        configuration.setPathStyle(properties.isPathStyleAccessEnabled());
        configuration.setMaxConnections(properties.getMaxConnections());
        configuration.setConnectionTimeout(properties.getConnectionTimeoutMs());
        configuration.setSocketTimeout(properties.getSocketTimeoutMs());
        return new ObsClient(
                requireText(properties.getAccessKey(), "Huawei S3 access-key 不能为空"),
                requireText(properties.getSecretKey(), "Huawei S3 secret-key 不能为空"),
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
