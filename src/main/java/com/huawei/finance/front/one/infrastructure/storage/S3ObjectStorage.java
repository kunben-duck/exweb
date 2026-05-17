package com.huawei.finance.front.one.infrastructure.storage;

import com.huawei.finance.front.one.application.integration.document.ObjectStorage;
import com.huawei.finance.front.one.domain.document.StoredObject;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 对象存储实现。
 *
 * <p>文档二进制内容进入 S3，openGauss 仍只保存 bucket、objectKey、contentType 等元数据。
 * objectKey 按 prefix/tenant/date/uuid-filename 组织，既隔离租户，又避免同名文件覆盖。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "s3")
public class S3ObjectStorage implements ObjectStorage {
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;

    public S3ObjectStorage(S3Client s3Client,
                           @Value("${financeex.storage.s3.bucket:}") String bucket,
                           @Value("${financeex.storage.s3.key-prefix:documents}") String keyPrefix) {
        this.s3Client = s3Client;
        this.bucket = requireText(bucket, "S3 bucket 不能为空");
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public StoredObject putObject(String tenantId, String originalFilename, String contentType, long sizeBytes, InputStream inputStream) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
        try {
            String objectKey = buildObjectKey(tenantId, originalFilename);
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey);
            if (hasText(contentType)) {
                request.contentType(contentType.trim());
            }
            s3Client.putObject(request.build(), RequestBody.fromInputStream(inputStream, sizeBytes));
            return new StoredObject(bucket, objectKey, sizeBytes, contentType);
        } catch (Exception ex) {
            throw new IllegalStateException("文档写入 S3 对象存储失败", ex);
        }
    }

    @Override
    public StoredObjectContent getObject(String bucket, String objectKey) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(requireText(bucket, "S3 bucket 不能为空"))
                    .key(requireText(objectKey, "S3 objectKey 不能为空"))
                    .build();
            var response = s3Client.getObject(request);
            return new StoredObjectContent(bucket, objectKey,
                    response.response().contentLength() == null ? -1L : response.response().contentLength(),
                    response.response().contentType(), response);
        } catch (Exception ex) {
            throw new IllegalStateException("文档读取 S3 对象存储失败", ex);
        }
    }

    @Override
    public String provider() {
        return "s3";
    }

    private String buildObjectKey(String tenantId, String originalFilename) {
        String tenantPath = sanitizePathSegment(requireText(tenantId, "租户 ID 不能为空"));
        String filename = sanitizeFilename(originalFilename);
        String datePath = DATE_PATH.format(Instant.now());
        String objectName = UUID.randomUUID() + "-" + filename;
        if (keyPrefix.isBlank()) {
            return tenantPath + "/" + datePath + "/" + objectName;
        }
        return keyPrefix + "/" + tenantPath + "/" + datePath + "/" + objectName;
    }

    private String sanitizeFilename(String originalFilename) {
        String filename = hasText(originalFilename) ? originalFilename.trim() : "document";
        filename = filename.replace('\\', '/');
        int lastSlash = filename.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }
        filename = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        filename = filename.replaceAll("_+", "_");
        if (filename.isBlank() || ".".equals(filename) || "..".equals(filename)) {
            filename = "document";
        }
        return filename.length() > 180 ? filename.substring(filename.length() - 180) : filename;
    }

    private String sanitizePathSegment(String value) {
        String sanitized = value.trim().replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
        if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
            throw new IllegalArgumentException("租户 ID 不能作为对象存储路径");
        }
        return sanitized;
    }

    private String normalizePrefix(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("^/+", "").replaceAll("/+$", "");
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
