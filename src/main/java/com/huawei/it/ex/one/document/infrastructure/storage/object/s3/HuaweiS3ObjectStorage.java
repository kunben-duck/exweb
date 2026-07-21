package com.huawei.it.ex.one.document.infrastructure.storage.object.s3;

import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.huawei.it.ex.one.document.application.client.ObjectStorage;
import com.huawei.it.ex.one.document.domain.StoredObject;
import com.huawei.it.ex.one.document.domain.StoredObjectContent;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 华为 OBS S3 对象存储实现。
 *
 * <p>文档二进制内容进入华为对象存储，数据库仍只保存 bucket、objectKey、contentType 等元数据。
 * provider 使用 {@code huawei-s3}，表示当前通过华为 OBS Java SDK 接入华为 S3/OBS 能力。
 * objectKey 按 prefix/tenant/date/uuid-filename 组织，既隔离租户，又避免同名文件覆盖。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "huawei-s3")
public class HuaweiS3ObjectStorage implements ObjectStorage {
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final HuaweiS3Operations huaweiS3;
    private final String bucket;
    private final String keyPrefix;

    public HuaweiS3ObjectStorage(HuaweiS3Operations huaweiS3,
                                 @Value("${financeex.storage.huawei-s3.bucket:}") String bucket,
                                 @Value("${financeex.storage.huawei-s3.key-prefix:documents}") String keyPrefix) {
        this.huaweiS3 = huaweiS3;
        this.bucket = requireText(bucket, "Huawei S3 bucket 不能为空");
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public StoredObject putObject(String tenantId, String originalFilename, String contentType, long sizeBytes, InputStream inputStream) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
        try {
            String objectKey = buildObjectKey(tenantId, originalFilename);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(sizeBytes);
            if (hasText(contentType)) {
                metadata.setContentType(contentType.trim());
            }
            PutObjectRequest request = new PutObjectRequest(bucket, objectKey, inputStream);
            request.setMetadata(metadata);
            request.setAutoClose(false);
            huaweiS3.putObject(request);
            return new StoredObject(bucket, objectKey, sizeBytes, contentType);
        } catch (Exception ex) {
            throw new IllegalStateException("文档写入 Huawei S3 对象存储失败", ex);
        }
    }

    @Override
    public StoredObjectContent getObject(String bucket, String objectKey) {
        try {
            ObsObject response = huaweiS3.getObject(
                    requireText(bucket, "Huawei S3 bucket 不能为空"),
                    requireText(objectKey, "Huawei S3 objectKey 不能为空"));
            ObjectMetadata metadata = response.getMetadata();
            Long contentLength = metadata == null ? null : metadata.getContentLength();
            String contentType = metadata == null ? null : metadata.getContentType();
            return new StoredObjectContent(bucket, objectKey,
                    contentLength == null ? -1L : contentLength,
                    contentType, response.getObjectContent());
        } catch (Exception ex) {
            throw new IllegalStateException("文档读取 Huawei S3 对象存储失败", ex);
        }
    }

    @Override
    public String provider() {
        return "huawei-s3";
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
